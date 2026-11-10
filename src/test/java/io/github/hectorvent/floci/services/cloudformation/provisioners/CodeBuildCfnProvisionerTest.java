package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codebuild.CodeBuildJsonHandler;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The CodeBuild CFN provisioner in isolation, with the handler mocked. */
class CodeBuildCfnProvisionerTest {

    private final CodeBuildJsonHandler handler = mock(CodeBuildJsonHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeBuildCfnProvisioner provisioner =
            new CodeBuildCfnProvisioner(handler, mock(RegionResolver.class), mapper);

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "cfn-stack");
    }

    @Test
    void projectPropertiesAreLoweredIncludingIrregularBuildspec() throws Exception {
        ObjectNode project = mapper.createObjectNode();
        project.putObject("project").put("arn",
                "arn:aws:codebuild:us-east-1:000000000000:project/installer");
        when(handler.handle(eq("CreateProject"), any(), eq("us-east-1"), eq("000000000000")))
                .thenReturn(Response.ok(project).build());

        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "installer");
        props.put("ServiceRole", "arn:aws:iam::000000000000:role/build");
        ObjectNode source = props.putObject("Source");
        source.put("Type", "CODEPIPELINE");
        source.put("BuildSpec", "version: 0.2");
        props.putObject("Artifacts").put("Type", "CODEPIPELINE");
        ObjectNode environment = props.putObject("Environment");
        environment.put("ComputeType", "BUILD_GENERAL1_MEDIUM");
        environment.put("Image", "aws/codebuild/standard:7.0");
        environment.putArray("EnvironmentVariables").addObject()
                .put("Name", "PREFIX").put("Value", "AWSAccelerator").put("Type", "PLAINTEXT");

        StackResource r = new StackResource();
        r.setLogicalId("Installer");
        r.setResourceType("AWS::CodeBuild::Project");
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).handle(eq("CreateProject"), request.capture(), eq("us-east-1"), eq("000000000000"));
        JsonNode sent = request.getValue();
        assertEquals("installer", sent.path("name").asText());
        assertEquals("CODEPIPELINE", sent.path("source").path("type").asText());
        assertEquals("version: 0.2", sent.path("source").path("buildspec").asText());
        assertEquals("BUILD_GENERAL1_MEDIUM", sent.path("environment").path("computeType").asText());
        assertEquals("PREFIX", sent.path("environment").path("environmentVariables")
                .get(0).path("name").asText());

        assertEquals("installer", r.getPhysicalId());
        assertEquals("arn:aws:codebuild:us-east-1:000000000000:project/installer",
                r.getAttributes().get("Arn"));
    }

    @Test
    void existingPhysicalIdRoutesToUpdateProject() throws Exception {
        when(handler.handle(eq("UpdateProject"), any(), any(), any()))
                .thenReturn(Response.ok(mapper.createObjectNode()).build());
        StackResource r = new StackResource();
        r.setLogicalId("Installer");
        r.setResourceType("AWS::CodeBuild::Project");
        r.setAttributes(new HashMap<>());
        r.setPhysicalId("installer");

        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "installer");
        provisioner.provision(r, props, ctx());

        verify(handler).handle(eq("UpdateProject"), any(), eq("us-east-1"), eq("000000000000"));
    }

    @Test
    void mapEntityFromHandlerIsConvertedNotDropped() throws Exception {
        // The real handler returns Response.ok(Map.of(...)) — a Map/POJO entity, never a JsonNode —
        // so a plain instanceof check silently drops the project and falls back to the guessed ARN.
        Map<String, Object> project = new HashMap<>();
        project.put("arn", "arn:aws:codebuild:us-east-1:000000000000:project/from-handler");
        Map<String, Object> entity = Map.of("project", project);
        when(handler.handle(eq("CreateProject"), any(), eq("us-east-1"), eq("000000000000")))
                .thenReturn(Response.ok(entity).build());

        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "installer");
        StackResource r = new StackResource();
        r.setLogicalId("Installer");
        r.setResourceType("AWS::CodeBuild::Project");
        r.setAttributes(new HashMap<>());

        provisioner.provision(r, props, ctx());

        assertEquals("arn:aws:codebuild:us-east-1:000000000000:project/from-handler",
                r.getAttributes().get("Arn"));
    }

    private static final String FLEET_ARN =
            "arn:aws:codebuild:us-east-1:000000000000:fleet/runners:11111111-2222-3333-4444-555555555555";

    private static StackResource fleet(String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Runners");
        r.setResourceType("AWS::CodeBuild::Fleet");
        r.setAttributes(new HashMap<>());
        r.setPhysicalId(priorPhysicalId);
        return r;
    }

    private ObjectNode fleetProps() {
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "runners");
        props.put("BaseCapacity", 1);
        props.put("ComputeType", "BUILD_GENERAL1_SMALL");
        props.put("EnvironmentType", "ARM_CONTAINER");
        props.put("OverflowBehavior", "ON_DEMAND");
        props.putObject("FleetVpcConfig").put("VpcId", "vpc-1").putArray("Subnets").add("subnet-1");
        return props;
    }

    private Response fleetResponse() {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("fleet").put("arn", FLEET_ARN);
        return Response.ok(body).build();
    }

    @Test
    void fleetIsCreatedWithTheApiNamesAndAnswersRefAndArnWithItsArn() throws Exception {
        when(handler.handle(eq("CreateFleet"), any(), eq("us-east-1"), eq("000000000000")))
                .thenReturn(fleetResponse());
        StackResource r = fleet(null);

        provisioner.provision(r, fleetProps(), ctx());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).handle(eq("CreateFleet"), request.capture(), eq("us-east-1"), eq("000000000000"));
        JsonNode sent = request.getValue();
        assertEquals("runners", sent.path("name").asText());
        assertEquals(1, sent.path("baseCapacity").asInt());
        assertEquals("ARM_CONTAINER", sent.path("environmentType").asText());
        assertEquals("ON_DEMAND", sent.path("overflowBehavior").asText());
        assertEquals("vpc-1", sent.path("vpcConfig").path("vpcId").asText());
        assertEquals(false, sent.has("fleetVpcConfig"));
        assertEquals(FLEET_ARN, r.getPhysicalId());
        assertEquals(FLEET_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void fleetKeepingItsNameIsUpdatedInPlaceByItsArn() throws Exception {
        when(handler.handle(eq("UpdateFleet"), any(), eq("us-east-1"), eq("000000000000")))
                .thenReturn(fleetResponse());
        StackResource r = fleet(FLEET_ARN);
        r.getAttributes().put("Arn", FLEET_ARN);

        provisioner.provision(r, fleetProps(), new ProvisionContext(ctx().engine(), "us-east-1", "000000000000",
                "cfn-stack", FLEET_ARN));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).handle(eq("UpdateFleet"), request.capture(), eq("us-east-1"), eq("000000000000"));
        assertEquals(FLEET_ARN, request.getValue().path("arn").asText());
        assertEquals(false, request.getValue().has("name"));
        verify(handler, never()).handle(eq("CreateFleet"), any(), any(), any());
        assertEquals(FLEET_ARN, r.getPhysicalId());
    }

    @Test
    void fleetDeleteSendsItsArnAndToleratesOneAlreadyGone() throws Exception {
        when(handler.handle(eq("DeleteFleet"), any(), eq("us-east-1"), eq("000000000000")))
                .thenThrow(new AwsException("ResourceNotFoundException", "Fleet not found", 400));

        provisioner.delete("AWS::CodeBuild::Fleet", FLEET_ARN, "us-east-1");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).handle(eq("DeleteFleet"), request.capture(), eq("us-east-1"), eq("000000000000"));
        assertEquals(FLEET_ARN, request.getValue().path("arn").asText());
    }
}
