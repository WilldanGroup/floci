package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codebuild.CodeBuildJsonHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for CodeBuild: {@code AWS::CodeBuild::Project} and
 * {@code AWS::CodeBuild::Fleet}.
 *
 * <p>CloudFormation properties are PascalCase while the CodeBuild API is camelCase; the
 * recursive first-letter-lowercase transform maps them, with one irregular field —
 * {@code BuildSpec} is {@code buildspec} (all lowercase) on the wire. A fleet's
 * {@code FleetVpcConfig} and {@code FleetProxyConfiguration} are the API's {@code vpcConfig} and
 * {@code proxyConfiguration}.</p>
 *
 * <p>A fleet answers {@code Ref} and {@code Fn::GetAtt Arn} with its ARN, as the CloudFormation
 * Template Reference's page for {@code AWS::CodeBuild::Fleet} has it. Its {@code Name} is
 * createOnly: a template that changes it stands a new fleet and the old one is deleted once the
 * update commits; every other property is updated in place.</p>
 */
@ApplicationScoped
public class CodeBuildCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CodeBuildCfnProvisioner.class);
    private static final String PROJECT = "AWS::CodeBuild::Project";
    private static final String FLEET = "AWS::CodeBuild::Fleet";
    private static final int FLEET_NAME_MAX_LENGTH = 128;

    private final CodeBuildJsonHandler codeBuildJsonHandler;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public CodeBuildCfnProvisioner(CodeBuildJsonHandler codeBuildJsonHandler,
                                   RegionResolver regionResolver, ObjectMapper mapper) {
        this.codeBuildJsonHandler = codeBuildJsonHandler;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(PROJECT, FLEET);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case PROJECT -> provisionProject(r, props, ctx);
            case FLEET -> provisionFleet(r, props, ctx);
            default -> throw new IllegalStateException(
                    "CodeBuildCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionProject(StackResource r, JsonNode props, ProvisionContext ctx) {
        ObjectNode request = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        boolean update = r.getPhysicalId() != null && !r.getPhysicalId().isBlank();
        String name = request.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = update ? r.getPhysicalId() : ctx.generatePhysicalName(r.getLogicalId(), 150, false);
            request.put("name", name);
        }

        JsonNode response = handle(update ? "UpdateProject" : "CreateProject",
                request, ctx.region(), ctx.accountId());

        r.setPhysicalId(name);
        r.getAttributes().put("Arn",
                response.path("project").path("arn").asText(
                        AwsArnUtils.Arn.of("codebuild", ctx.region(), ctx.accountId(), "project/" + name).toString()));
    }

    private void provisionFleet(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        ObjectNode request = (ObjectNode) lowerKeys(ctx.engine().resolveNode(props));
        renameMember(request, "fleetVpcConfig", "vpcConfig");
        renameMember(request, "fleetProxyConfiguration", "proxyConfiguration");
        // A prior id that is no ARN is a dispatcher stub, which names no fleet to update.
        String priorName = ctx.isUpdate() && AwsArnUtils.isArn(ctx.priorPhysicalId())
                ? fleetName(ctx.priorPhysicalId())
                : null;
        String name = request.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = priorName != null
                    ? priorName
                    : ctx.generatePhysicalName(r.getLogicalId(), FLEET_NAME_MAX_LENGTH, false);
            request.put("name", name);
        }

        JsonNode response;
        if (name.equals(priorName)) {
            request.remove("name");
            request.put("arn", ctx.priorPhysicalId());
            if (!request.has("tags")) {
                request.putArray("tags");
            }
            response = handle("UpdateFleet", request, ctx.region(), ctx.accountId());
        } else {
            response = handle("CreateFleet", request, ctx.region(), ctx.accountId());
        }

        String arn = response.path("fleet").path("arn").asText();
        r.setPhysicalId(arn);
        r.getAttributes().put("Arn", arn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return FLEET.equals(resource.getResourceType()) && ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return FLEET.equals(resource.getResourceType()) ? ReplacementCleanup.cleanupPhysicalId(resource) : null;
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return FLEET.equals(resource.getResourceType())
                ? ReplacementCleanup.complete(resource, this::delete)
                : UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        if (FLEET.equals(resource.getResourceType())) {
            ReplacementCleanup.clear(resource);
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return FLEET.equals(resource.getResourceType()) && ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (FLEET.equals(resourceType)) {
            CfnDeletes.safeDelete("CodeBuild fleet", physicalId,
                    () -> handle("DeleteFleet", mapper.createObjectNode().put("arn", physicalId), region,
                            AwsArnUtils.parse(physicalId).accountId()),
                    "ResourceNotFoundException");
            return;
        }
        try {
            handle("DeleteProject", mapper.createObjectNode().put("name", physicalId),
                    region, regionResolver.getAccountId());
        } catch (AwsException e) {
            LOG.debugv("CodeBuild CFN delete of {0} tolerated: {1}", physicalId, e.getMessage());
        }
    }

    private JsonNode handle(String action, ObjectNode request, String region, String account) {
        try {
            Response response =
                    codeBuildJsonHandler.handle(action, request, region, account);
            try {
                // The handler returns Response.ok(Map.of(...)) for every JSON action, never a
                // JsonNode directly, so the entity must be converted rather than downcast.
                Object entity = response.getEntity();
                return entity instanceof JsonNode node ? node : mapper.valueToTree(entity);
            } finally {
                response.close();
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure",
                    "CodeBuild " + action + " failed: " + e.getMessage(), 500);
        }
    }

    /** The name in a fleet ARN, {@code fleet/<name>:<uuid>}. */
    private static String fleetName(String arn) {
        String resource = AwsArnUtils.parse(arn).resource();
        String id = resource.substring(resource.indexOf('/') + 1);
        int separator = id.lastIndexOf(':');
        return separator < 0 ? id : id.substring(0, separator);
    }

    private static void renameMember(ObjectNode node, String from, String to) {
        JsonNode value = node.remove(from);
        if (value != null) {
            node.set(to, value);
        }
    }

    private JsonNode lowerKeys(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode result = mapper.createObjectNode();
            objectNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String lowered = "BuildSpec".equals(key)
                        ? "buildspec"
                        : key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
                result.set(lowered, lowerKeys(entry.getValue()));
            });
            return result;
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode result = mapper.createArrayNode();
            arrayNode.forEach(entry -> result.add(lowerKeys(entry)));
            return result;
        }
        return node;
    }
}
