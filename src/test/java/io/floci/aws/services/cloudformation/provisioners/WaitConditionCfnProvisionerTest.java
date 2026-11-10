package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.WaitConditionSignalStore;
import io.floci.aws.services.cloudformation.WaitConditionSignalStore.Signal;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The wait condition and its handle in isolation, with a real signal store and no stack around them. */
class WaitConditionCfnProvisionerTest {

    private static final String STACK_ID =
            "arn:aws:cloudformation:us-east-1:000000000000:stack/wait-stack/11111111-2222-3333-4444-555555555555";
    private static final String HANDLE = "us-east-1/000000000000/wait-stack/Handle/abc";
    private static final String HANDLE_URL = "https://localhost.floci.io:4566/cloudformation-waitcondition/" + HANDLE;

    private final WaitConditionSignalStore signals = new WaitConditionSignalStore();
    private final WaitConditionCfnProvisioner provisioner = new WaitConditionCfnProvisioner(signals);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.stackId()).thenReturn(STACK_ID);
        return new ProvisionContext(engine, "us-east-1", "000000000000", "wait-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode condition(String count, String timeout) {
        ObjectNode props = mapper.createObjectNode().put("Handle", HANDLE_URL).put("Timeout", timeout);
        if (count != null) {
            props.put("Count", Integer.parseInt(count));
        }
        return props;
    }

    @Test
    void theHandlesUrlIsBuiltOnTheBaseACustomResourcesResponseUrlIs() {
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.customResourceResponseBaseUrl()).thenReturn("https://localhost.floci.io:4566");
        StackResource r = resource("AWS::CloudFormation::WaitConditionHandle", "Handle");

        new WaitConditionHandleCfnProvisioner(endpoint).provision(r, null, ctx(null));

        assertTrue(r.getPhysicalId().startsWith(
                "https://localhost.floci.io:4566/cloudformation-waitcondition/us-east-1/000000000000/wait-stack/Handle/"),
                r.getPhysicalId());
        assertEquals(r.getPhysicalId(), r.getAttributes().get("Id"));
    }

    @Test
    void signalsSentBeforeTheWaitCountAndDataIsTheirUniqueIdsAndData() {
        signals.receive(HANDLE, new Signal("SUCCESS", "ID1", "first", "one"));
        signals.receive(HANDLE, new Signal("SUCCESS", "ID1", "retransmitted", "again"));
        signals.receive(HANDLE, new Signal("SUCCESS", "ID2", "second", "two"));
        StackResource r = resource("AWS::CloudFormation::WaitCondition", "Condition");

        provisioner.provision(r, condition("2", "5"), ctx(null));

        assertEquals(STACK_ID + "/Condition", r.getPhysicalId());
        assertEquals(STACK_ID + "/Condition", r.getAttributes().get("Id"));
        assertEquals("{\"ID1\":\"first\",\"ID2\":\"second\"}", r.getAttributes().get("Data"));
    }

    @Test
    void aSignalSentWhileItWaitsReleasesIt() throws Exception {
        StackResource r = resource("AWS::CloudFormation::WaitCondition", "Condition");
        Thread signaller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            signals.receive(HANDLE, new Signal("SUCCESS", "late", "done", ""));
        });
        signaller.start();

        provisioner.provision(r, condition(null, "10"), ctx(null));

        signaller.join();
        assertEquals("{\"late\":\"done\"}", r.getAttributes().get("Data"));
    }

    @Test
    void aFailureSignalFailsItWithItsReason() {
        signals.receive(HANDLE, new Signal("SUCCESS", "ok", "", ""));
        signals.receive(HANDLE, new Signal("FAILURE", "bad", "", "it broke"));

        AwsException failed = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::CloudFormation::WaitCondition", "Condition"), condition("2", "5"), ctx(null)));

        assertEquals("WaitCondition received failed message: 'it broke' for uniqueId: bad", failed.getMessage());
    }

    @Test
    void theTimeoutFailsItSayingHowManyArrived() {
        AwsException failed = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::CloudFormation::WaitCondition", "Condition"), condition("1", "1"), ctx(null)));

        assertEquals("WaitCondition timed out. Received 0 conditions when expecting 1", failed.getMessage());
    }

    @Test
    void anUpdateKeepsTheConditionAndWaitsOnNothing() {
        StackResource r = resource("AWS::CloudFormation::WaitCondition", "Condition");
        r.getAttributes().put("Id", STACK_ID + "/Condition");
        r.getAttributes().put("Data", "{\"ID1\":\"first\"}");

        provisioner.provision(r, condition("5", "1"), ctx(STACK_ID + "/Condition"));

        assertEquals(STACK_ID + "/Condition", r.getPhysicalId());
        assertEquals("{\"ID1\":\"first\"}", r.getAttributes().get("Data"));
    }

    @Test
    void aHandleThatIsNoHandlesUrlIsRefused() {
        ObjectNode props = mapper.createObjectNode().put("Handle", "https://example.com/other").put("Timeout", "5");

        assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::CloudFormation::WaitCondition", "Condition"), props, ctx(null)));
    }
}
