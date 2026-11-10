package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::CodeArtifact::Domain} and {@code AWS::CodeArtifact::Repository} over
 * {@link CodeArtifactService}.
 *
 * <p>Both answer {@code Ref} with the ARN, as the CloudFormation Template Reference's pages for the
 * two types have it, and set every attribute those pages list: a domain's {@code Arn}, {@code Name},
 * {@code Owner} and {@code EncryptionKey}, a repository's {@code Arn}, {@code Name},
 * {@code DomainName} and {@code DomainOwner}. A repository's {@code ExternalConnections} are
 * associated after its create, which is how the API takes them, and on update the connections the
 * template drops are disassociated before the upstreams change and the ones it adds associated
 * after, since CodeArtifact refuses a repository holding both at once.
 *
 * <p>The names, a domain's {@code EncryptionKey} and a repository's {@code DomainName} and
 * {@code DomainOwner} are createOnly: a change to one creates the replacement and the displaced
 * entity is deleted once the update commits. A replacement under the same name cannot exist beside
 * the entity it replaces, so an update that keeps the name and changes the encryption key is
 * refused, as CloudFormation refuses a replacement of a custom-named resource.
 */
@ApplicationScoped
public class CodeArtifactCfnProvisioner implements CfnResourceProvisioner {

    private static final String DOMAIN = "AWS::CodeArtifact::Domain";
    private static final String REPOSITORY = "AWS::CodeArtifact::Repository";
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private final CodeArtifactService codeArtifactService;

    public CodeArtifactCfnProvisioner(CodeArtifactService codeArtifactService) {
        this.codeArtifactService = codeArtifactService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(DOMAIN, REPOSITORY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case DOMAIN -> provisionDomain(r, props, ctx);
            case REPOSITORY -> provisionRepository(r, props, ctx);
            default -> throw new IllegalStateException(
                    "CodeArtifactCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionDomain(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String name = required(ctx.resolveOptional(props, "DomainName"), "DomainName", r);
        String encryptionKey = blankToNull(ctx.resolveOptional(props, "EncryptionKey"));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String policy = policyDocument(props, ctx);
        String region = ctx.region();

        boolean reusing = ctx.isUpdate() && name.equals(attributesBefore.get("Name"));
        CodeArtifactDomain domain;
        if (reusing) {
            String storedKey = attributesBefore.get("EncryptionKey");
            if (encryptionKey != null && storedKey != null && !encryptionKey.equals(storedKey)) {
                throw new AwsException("ValidationError", "Updating EncryptionKey of domain " + name
                        + " requires replacement, which a custom-named domain cannot take.", 400);
            }
            domain = codeArtifactService.describeDomain(region, name, null).domain();
            reconcileTags(domain.getArn(), tags);
            if (policy != null) {
                codeArtifactService.putDomainPermissionsPolicy(region, name, null, policy, null);
            } else if (domain.getPolicyDocument() != null) {
                codeArtifactService.deleteDomainPermissionsPolicy(region, name, null, null);
            }
        } else {
            domain = codeArtifactService.createDomain(region, name, encryptionKey, tags).domain();
            if (policy != null) {
                codeArtifactService.putDomainPermissionsPolicy(region, name, null, policy, null);
            }
        }

        r.setPhysicalId(domain.getArn());
        r.getAttributes().put("Arn", domain.getArn());
        r.getAttributes().put("Name", domain.getName());
        r.getAttributes().put("Owner", domain.getOwner());
        r.getAttributes().put("EncryptionKey", domain.getEncryptionKey());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionRepository(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String name = required(ctx.resolveOptional(props, "RepositoryName"), "RepositoryName", r);
        String domainName = required(ctx.resolveOptional(props, "DomainName"), "DomainName", r);
        String domainOwner = blankToNull(ctx.resolveOptional(props, "DomainOwner"));
        String description = ctx.resolveOptional(props, "Description");
        List<String> upstreams = ctx.resolveStringList(props, "Upstreams");
        List<String> connections = ctx.resolveStringList(props, "ExternalConnections");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String policy = policyDocument(props, ctx);
        String region = ctx.region();
        String owner = domainOwner != null ? domainOwner : ctx.accountId();

        boolean reusing = ctx.isUpdate() && name.equals(attributesBefore.get("Name"))
                && domainName.equals(attributesBefore.get("DomainName"))
                && owner.equals(attributesBefore.get("DomainOwner"));
        CodeArtifactRepository repository;
        if (reusing) {
            CodeArtifactRepository current = codeArtifactService.describeRepository(region, domainName, owner, name);
            for (ExternalConnection connection : List.copyOf(current.getExternalConnections())) {
                if (!connections.contains(connection.getExternalConnectionName())) {
                    codeArtifactService.disassociateExternalConnection(region, domainName, owner, name,
                            connection.getExternalConnectionName());
                }
            }
            // A description the template drops is cleared, as the whole template is the desired state.
            repository = codeArtifactService.updateRepository(region, domainName, owner, name,
                    description != null ? description : "", upstreams);
            List<String> held = repository.getExternalConnections().stream()
                    .map(ExternalConnection::getExternalConnectionName)
                    .toList();
            for (String connection : connections) {
                if (!held.contains(connection)) {
                    repository = codeArtifactService.associateExternalConnection(region, domainName, owner, name,
                            connection);
                }
            }
            reconcileTags(repository.getArn(), tags);
            if (policy != null) {
                codeArtifactService.putRepositoryPermissionsPolicy(region, domainName, owner, name, policy, null);
            } else if (repository.getPolicyDocument() != null) {
                codeArtifactService.deleteRepositoryPermissionsPolicy(region, domainName, owner, name, null);
            }
        } else {
            repository = codeArtifactService.createRepository(region, domainName, owner, name, description,
                    upstreams, tags);
            for (String connection : connections) {
                repository = codeArtifactService.associateExternalConnection(region, domainName, owner, name,
                        connection);
            }
            if (policy != null) {
                codeArtifactService.putRepositoryPermissionsPolicy(region, domainName, owner, name, policy, null);
            }
        }

        r.setPhysicalId(repository.getArn());
        r.getAttributes().put("Arn", repository.getArn());
        r.getAttributes().put("Name", repository.getName());
        r.getAttributes().put("DomainName", repository.getDomainName());
        r.getAttributes().put("DomainOwner", repository.getDomainOwner());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Deletes the domain or repository the ARN names, in the account and region it names. One that
     * is already gone is deleted; a domain still holding repositories fails, as on AWS.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(physicalId);
        String[] parts = arn.resource().split("/");
        if (DOMAIN.equals(resourceType)) {
            CfnDeletes.safeDelete("CodeArtifact domain", physicalId,
                    () -> codeArtifactService.deleteDomain(arn.region(), parts[1], arn.accountId()), NOT_FOUND);
        } else if (REPOSITORY.equals(resourceType)) {
            CfnDeletes.safeDelete("CodeArtifact repository", physicalId,
                    () -> codeArtifactService.deleteRepository(arn.region(), parts[1], arn.accountId(), parts[2]),
                    NOT_FOUND);
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    /** Drives the resource's tags to the template's set: dropped keys are untagged, the rest written. */
    private void reconcileTags(String arn, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(codeArtifactService.listTagsForResource(arn), desired);
        if (!stale.isEmpty()) {
            codeArtifactService.untagResource(arn, stale);
        }
        if (!desired.isEmpty()) {
            codeArtifactService.tagResource(arn, desired);
        }
    }

    /** {@code PermissionsPolicyDocument} as JSON text, or null when the template declares none. */
    private static String policyDocument(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("PermissionsPolicyDocument")) {
            return null;
        }
        return blankToNull(ctx.engine().resolveJsonAttributeStrict(props.get("PermissionsPolicyDocument")));
    }

    private static String required(String value, String property, StackResource r) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", "Property " + property + " of " + r.getLogicalId()
                    + " (" + r.getResourceType() + ") is required.", 400);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
