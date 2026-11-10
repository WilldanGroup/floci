package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The KMS CFN provisioner's key and replica key in isolation, against a mocked {@link KmsService}. */
class KmsCfnProvisionerTest {

    private static final String KEY_ID = "mrk-1234abcd12ab34cd56ef1234567890ab";
    private static final String PRIMARY_ARN = "arn:aws:kms:us-east-1:000000000000:key/" + KEY_ID;
    private static final String REPLICA_ARN = "arn:aws:kms:us-west-2:000000000000:key/" + KEY_ID;
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private final KmsService kms = mock(KmsService.class);
    private final KmsCfnProvisioner provisioner = new KmsCfnProvisioner(kms);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String region, String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        return new ProvisionContext(engine, region, "000000000000", "kms-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String priorPhysicalId, Map<String, String> attributes) {
        StackResource r = new StackResource();
        r.setLogicalId("ClassKey");
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private static KmsKey key(String arn, boolean enabled) {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(arn);
        key.setEnabled(enabled);
        return key;
    }

    private ObjectNode replicaProps() throws Exception {
        ObjectNode props = mapper.createObjectNode();
        props.put("PrimaryKeyArn", PRIMARY_ARN);
        props.put("Description", "replica");
        props.set("KeyPolicy", mapper.readTree(POLICY));
        return props;
    }

    @Test
    void multiRegionKeyIsCreatedAsAPrimaryWithItsPolicy() throws Exception {
        when(kms.createKey(eq("class key"), isNull(), isNull(), eq(POLICY), anyMap(), isNull(),
                eq(true), eq("us-east-1"))).thenReturn(key(PRIMARY_ARN, true));
        ObjectNode props = mapper.createObjectNode().put("Description", "class key").put("MultiRegion", true);
        props.set("KeyPolicy", mapper.readTree(POLICY));
        StackResource r = resource("AWS::KMS::Key", null, Map.of());

        provisioner.provision(r, props, ctx("us-east-1", null));

        assertEquals(KEY_ID, r.getPhysicalId());
        assertEquals(PRIMARY_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void replicaIsReplicatedFromThePrimarysRegionIntoTheStacksAndAnswersItsKeyIdAndArn() throws Exception {
        when(kms.replicateKey(eq(PRIMARY_ARN), eq("replica"), eq(POLICY), anyMap(), eq("us-west-2"),
                eq("us-east-1"))).thenReturn(key(REPLICA_ARN, true));
        StackResource r = resource("AWS::KMS::ReplicaKey", null, Map.of());

        provisioner.provision(r, replicaProps(), ctx("us-west-2", null));

        assertEquals(KEY_ID, r.getPhysicalId());
        Map<String, String> attributes = new HashMap<>(r.getAttributes());
        attributes.keySet().removeIf(name -> name.startsWith("__Floci"));
        assertEquals(Map.of("Arn", REPLICA_ARN, "KeyId", KEY_ID), attributes);
        verify(kms, never()).disableKey(anyString(), anyString());
    }

    @Test
    void replicaOfTheSamePrimaryIsUpdatedInPlace() throws Exception {
        StackResource r = resource("AWS::KMS::ReplicaKey", KEY_ID,
                Map.of("Arn", REPLICA_ARN, "KeyId", KEY_ID, "__FlociPrimaryKeyArn", PRIMARY_ARN));
        when(kms.describeKey(KEY_ID, "us-west-2")).thenReturn(key(REPLICA_ARN, true));
        ObjectNode props = replicaProps().put("Enabled", "false");

        provisioner.provision(r, props, ctx("us-west-2", KEY_ID));

        verify(kms).putKeyPolicy(KEY_ID, POLICY, "us-west-2");
        verify(kms).updateKeyDescription(KEY_ID, "replica", "us-west-2");
        verify(kms).disableKey(KEY_ID, "us-west-2");
        verify(kms, never()).replicateKey(anyString(), any(), any(), anyMap(), anyString(), anyString());
        assertEquals(KEY_ID, r.getPhysicalId());
    }

    @Test
    void aStackDeleteLeavesAReplicaKeyAsItLeavesAKey() {
        provisioner.delete("AWS::KMS::ReplicaKey", KEY_ID, "us-west-2");

        verify(kms, never()).scheduleKeyDeletion(anyString(), anyInt(), anyString());
    }
}
