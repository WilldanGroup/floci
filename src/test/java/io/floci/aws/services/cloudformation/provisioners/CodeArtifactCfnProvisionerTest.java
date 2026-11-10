package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codeartifact.CodeArtifactService;
import io.floci.aws.services.codeartifact.CodeArtifactService.DomainView;
import io.floci.aws.services.codeartifact.model.CodeArtifactDomain;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The CodeArtifact CFN provisioner in isolation, against a mocked {@link CodeArtifactService}. */
class CodeArtifactCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String DOMAIN_ARN = "arn:aws:codeartifact:us-east-1:000000000000:domain/willdan";
    private static final String REPO_ARN = "arn:aws:codeartifact:us-east-1:000000000000:repository/willdan/npm-store";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:alias/aws/codeartifact";

    private final CodeArtifactService service = mock(CodeArtifactService.class);
    private final CodeArtifactCfnProvisioner provisioner = new CodeArtifactCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(v -> values.add(v.asText()));
            return values;
        });
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        return new ProvisionContext(engine, REGION, ACCOUNT, "pkg-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String priorPhysicalId, Map<String, String> attributes) {
        StackResource r = new StackResource();
        r.setLogicalId("Thing");
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private static CodeArtifactDomain domain() {
        CodeArtifactDomain d = new CodeArtifactDomain();
        d.setName("willdan");
        d.setOwner(ACCOUNT);
        d.setArn(DOMAIN_ARN);
        d.setEncryptionKey(KEY);
        return d;
    }

    private static CodeArtifactRepository repository(String... connections) {
        CodeArtifactRepository r = new CodeArtifactRepository();
        r.setName("npm-store");
        r.setDomainName("willdan");
        r.setDomainOwner(ACCOUNT);
        r.setArn(REPO_ARN);
        List<ExternalConnection> held = new ArrayList<>();
        for (String connection : connections) {
            held.add(new ExternalConnection(connection, "npm", "Available"));
        }
        r.setExternalConnections(held);
        r.setUpstreams(new ArrayList<>());
        r.setTags(new HashMap<>());
        return r;
    }

    private ObjectNode repositoryProps(String... connections) {
        ObjectNode props = mapper.createObjectNode();
        props.put("RepositoryName", "npm-store");
        props.put("DomainName", "willdan");
        ArrayNode list = props.putArray("ExternalConnections");
        for (String connection : connections) {
            list.add(connection);
        }
        return props;
    }

    @Test
    void domainAnswersRefWithItsArnAndEveryAttributeThePageLists() {
        when(service.createDomain(eq(REGION), eq("willdan"), isNull(), anyMap()))
                .thenReturn(new DomainView(domain(), 0, 0L));
        StackResource r = resource("AWS::CodeArtifact::Domain", null, Map.of());
        ObjectNode props = mapper.createObjectNode().put("DomainName", "willdan");

        provisioner.provision(r, props, ctx(null));

        assertEquals(DOMAIN_ARN, r.getPhysicalId());
        assertEquals(Map.of("Arn", DOMAIN_ARN, "Name", "willdan", "Owner", ACCOUNT, "EncryptionKey", KEY),
                publicAttributes(r));
    }

    @Test
    void domainUpdateThatChangesTheKeyOfTheSameNameIsRefused() {
        StackResource r = resource("AWS::CodeArtifact::Domain", DOMAIN_ARN,
                Map.of("Name", "willdan", "EncryptionKey", KEY));
        ObjectNode props = mapper.createObjectNode().put("DomainName", "willdan")
                .put("EncryptionKey", "arn:aws:kms:us-east-1:000000000000:key/other");

        AwsException refused = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(DOMAIN_ARN)));

        assertEquals("ValidationError", refused.getErrorCode());
        verify(service, never()).createDomain(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void repositoryIsCreatedThenItsExternalConnectionAssociated() {
        when(service.createRepository(eq(REGION), eq("willdan"), eq(ACCOUNT), eq("npm-store"), isNull(), anyList(),
                anyMap())).thenReturn(repository());
        when(service.associateExternalConnection(REGION, "willdan", ACCOUNT, "npm-store", "public:npmjs"))
                .thenReturn(repository("public:npmjs"));
        StackResource r = resource("AWS::CodeArtifact::Repository", null, Map.of());

        provisioner.provision(r, repositoryProps("public:npmjs"), ctx(null));

        InOrder order = inOrder(service);
        order.verify(service).createRepository(eq(REGION), eq("willdan"), eq(ACCOUNT), eq("npm-store"), isNull(),
                eq(List.of()), anyMap());
        order.verify(service).associateExternalConnection(REGION, "willdan", ACCOUNT, "npm-store", "public:npmjs");
        assertEquals(REPO_ARN, r.getPhysicalId());
        assertEquals(Map.of("Arn", REPO_ARN, "Name", "npm-store", "DomainName", "willdan", "DomainOwner", ACCOUNT),
                publicAttributes(r));
    }

    @Test
    void repositoryUpdateDropsAConnectionBeforeItsUpstreamsChangeAndAddsTheNewOneAfter() {
        Map<String, String> prior = Map.of("Arn", REPO_ARN, "Name", "npm-store", "DomainName", "willdan",
                "DomainOwner", ACCOUNT);
        when(service.describeRepository(REGION, "willdan", ACCOUNT, "npm-store"))
                .thenReturn(repository("public:npmjs"));
        when(service.updateRepository(eq(REGION), eq("willdan"), eq(ACCOUNT), eq("npm-store"), eq(""), anyList()))
                .thenReturn(repository());
        when(service.associateExternalConnection(REGION, "willdan", ACCOUNT, "npm-store", "public:pypi"))
                .thenReturn(repository("public:pypi"));
        when(service.listTagsForResource(REPO_ARN)).thenReturn(Map.of());
        StackResource r = resource("AWS::CodeArtifact::Repository", REPO_ARN, prior);

        provisioner.provision(r, repositoryProps("public:pypi"), ctx(REPO_ARN));

        InOrder order = inOrder(service);
        order.verify(service).disassociateExternalConnection(REGION, "willdan", ACCOUNT, "npm-store", "public:npmjs");
        order.verify(service).updateRepository(REGION, "willdan", ACCOUNT, "npm-store", "", List.of());
        order.verify(service).associateExternalConnection(REGION, "willdan", ACCOUNT, "npm-store", "public:pypi");
        verify(service, never()).createRepository(anyString(), anyString(), anyString(), anyString(), any(),
                anyList(), anyMap());
        assertEquals(REPO_ARN, r.getPhysicalId());
    }

    @Test
    void deleteReadsTheDomainAndRepositoryFromTheArnAndToleratesOneAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).deleteRepository("us-east-1", "willdan", ACCOUNT, "npm-store");

        provisioner.delete("AWS::CodeArtifact::Repository", REPO_ARN, REGION);
        provisioner.delete("AWS::CodeArtifact::Domain", DOMAIN_ARN, REGION);

        verify(service).deleteDomain("us-east-1", "willdan", ACCOUNT);
    }

    @Test
    void aDomainStillHoldingRepositoriesFailsItsDelete() {
        doThrow(new AwsException("ConflictException", "contains repositories", 409))
                .when(service).deleteDomain("us-east-1", "willdan", ACCOUNT);

        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::CodeArtifact::Domain", DOMAIN_ARN, REGION));
    }

    private static Map<String, String> publicAttributes(StackResource r) {
        Map<String, String> attributes = new HashMap<>(r.getAttributes());
        attributes.keySet().removeIf(key -> key.startsWith("__Floci"));
        return attributes;
    }

    @Test
    void servesBothTypes() {
        assertEquals(Set.of("AWS::CodeArtifact::Domain", "AWS::CodeArtifact::Repository"),
                provisioner.resourceTypes());
    }
}
