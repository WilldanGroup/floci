package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A custom resource property that is a {@code Ref} to a {@code CommaDelimitedList} or {@code List<...>}
 * parameter reaches the handler as a list of the parameter's members, each space trimmed, as
 * CloudFormation's Parameters syntax page has it ({@code "test,dev,prod"} and "a {@code Ref} would result
 * in {@code ["test","dev","prod"]}"), whether the value was the default, given at the create or handed
 * down by a parent stack. A {@code String} parameter holding commas stays one string.
 *
 * <p>The ServiceToken Lambda is mocked, as in {@code CloudFormationCustomResourceStackIdIntegrationTest},
 * and answers by a PUT of its result to the event's {@code ResponseURL}.
 */
@QuarkusTest
class CloudFormationCustomResourceListParameterIntegrationTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:list-parameter-probe";

    private CustomResourceProbe probe;

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        probe = CustomResourceProbe.stub(lambdaService, SERVICE_TOKEN, "list-parameter-probe");
    }

    @Test
    void aListParametersRefReachesTheHandlerAsItsMembers() throws InterruptedException {
        String stackName = "cr-list-param-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName, probeTemplate(), Map.of("RoleKeys", "Admin, ReadOnly"));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        JsonNode properties = probe.events().getFirst().get("ResourceProperties");
        assertEquals(List.of("Admin", "ReadOnly"), strings(properties.get("RoleKeys")), properties::toString);
        assertEquals(List.of("80", "443"), strings(properties.get("Ports")), properties::toString);
        assertEquals("a,b", properties.get("Name").asText(), properties::toString);
        assertEquals("Admin,ReadOnly", properties.get("Joined").asText(), properties::toString);

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    @Test
    void aStringAParentHandsAChildsListParameterReachesTheHandlerAsAList() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cr-list-param-templates-" + suffix;
        String childKey = "child-" + suffix + ".json";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(probeTemplate())
        .when().put("/" + bucket + "/" + childKey).then().statusCode(200);

        String parentTemplate = """
                {
                  "Parameters": {"TokenRoleKeys": {"Type": "String", "Default": "ReadOnly"}},
                  "Resources": {
                    "Child": {
                      "Type": "AWS::CloudFormation::Stack",
                      "Properties": {
                        "TemplateURL": "http://localhost/%s/%s",
                        "Parameters": {"RoleKeys": {"Ref": "TokenRoleKeys"}}
                      }
                    }
                  }
                }
                """.formatted(bucket, childKey);
        String parentName = "cr-list-param-parent-" + suffix;
        String parentStackId = createStack(parentName, parentTemplate, Map.of());
        awaitStackStatus(parentStackId, "CREATE_COMPLETE");

        JsonNode properties = probe.events().getFirst().get("ResourceProperties");
        assertTrue(properties.get("RoleKeys").isArray(), properties::toString);
        assertEquals(List.of("ReadOnly"), strings(properties.get("RoleKeys")), properties::toString);

        deleteStack(parentName);
        awaitStackStatus(parentStackId, "DELETE_COMPLETE");
    }

    private static String probeTemplate() {
        return """
                {
                  "Parameters": {
                    "RoleKeys": {"Type": "CommaDelimitedList", "Default": "ReadOnly"},
                    "Ports": {"Type": "List<Number>", "Default": "80, 443"},
                    "Name": {"Type": "String", "Default": "a,b"}
                  },
                  "Resources": {
                    "Probe": {
                      "Type": "Custom::ListParameterProbe",
                      "Properties": {
                        "ServiceToken": "%s",
                        "RoleKeys": {"Ref": "RoleKeys"},
                        "Ports": {"Ref": "Ports"},
                        "Name": {"Ref": "Name"},
                        "Joined": {"Fn::Join": [",", {"Ref": "RoleKeys"}]}
                      }
                    }
                  }
                }
                """.formatted(SERVICE_TOKEN);
    }

    private static List<String> strings(JsonNode node) {
        assertTrue(node != null && node.isArray(), "expected a list, found " + node);
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static String createStack(String stackName, String template, Map<String, String> parameters) {
        Map<String, String> params = new HashMap<>(
                Map.of("Action", "CreateStack", "StackName", stackName, "TemplateBody", template));
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            params.put("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            params.put("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        return XmlParser.extractFirst(cfn(params), "StackId", null);
    }

    private static void deleteStack(String stackName) {
        cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
    }

    /** Polls DescribeStacks by stack id and returns its XML once the stack reaches {@code status}. */
    private static String awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = cfn(Map.of("Action", "DescribeStacks", "StackName", stackId));
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return xml;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
        return xml;
    }

    private static String cfn(Map<String, String> params) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParams(params)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().asString();
    }
}
