package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands a compute fleet as Switchboard's build fleet declares one, with a project whose
 * {@code Environment.Fleet.FleetArn} is the fleet's {@code Ref}, reads both back through
 * CodeBuild's BatchGetFleets, ListFleets and BatchGetProjects, updates the fleet in place and
 * deletes the stack.
 */
@QuarkusTest
class CodeBuildFleetCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/cloudformation/aws4_request";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STACK = "codebuild-fleet-cfn-it";
    private static final String FLEET = "cfn-it-runners";
    private static final String PROJECT = "cfn-it-runner-project";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Capacity": {"Type": "Number"},
            "EnvironmentType": {"Type": "String"}
          },
          "Resources": {
            "Fleet": {
              "Type": "AWS::CodeBuild::Fleet",
              "Properties": {
                "Name": "%1$s",
                "BaseCapacity": {"Ref": "Capacity"},
                "ComputeType": "BUILD_GENERAL1_SMALL",
                "EnvironmentType": {"Ref": "EnvironmentType"},
                "OverflowBehavior": "ON_DEMAND",
                "Tags": [{"Key": "role", "Value": "runner"}]
              }
            },
            "Project": {
              "Type": "AWS::CodeBuild::Project",
              "Properties": {
                "Name": "%2$s",
                "ServiceRole": "arn:aws:iam::000000000000:role/runner",
                "Artifacts": {"Type": "NO_ARTIFACTS"},
                "Source": {"Type": "NO_SOURCE", "BuildSpec": "version: 0.2"},
                "Environment": {
                  "Type": {"Ref": "EnvironmentType"},
                  "ComputeType": "BUILD_GENERAL1_SMALL",
                  "Image": "aws/codebuild/amazonlinux-aarch64-standard:3.0",
                  "Fleet": {"FleetArn": {"Ref": "Fleet"}}
                }
              }
            }
          },
          "Outputs": {
            "FleetRef": {"Value": {"Ref": "Fleet"}},
            "FleetArn": {"Value": {"Fn::GetAtt": ["Fleet", "Arn"]}}
          }
        }
        """.formatted(FLEET, PROJECT);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void standsAFleetAProjectBuildsOnUpdatesItAndDeletesIt() {
        cloudFormation("CreateStack", "1", "ARM_CONTAINER");
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        Map<String, String> created = outputs();
        String arn = created.get("FleetRef");

        assertEquals(arn, created.get("FleetArn"));
        assertThat(arn,
                matchesPattern("arn:aws:codebuild:us-east-1:000000000000:fleet/" + FLEET + ":[0-9a-f-]{36}"));
        codeBuild("BatchGetFleets", "{\"names\":[\"" + arn + "\"]}")
                .body("fleets.arn", contains(arn))
                .body("fleets[0].name", equalTo(FLEET))
                .body("fleets[0].baseCapacity", equalTo(1))
                .body("fleets[0].computeType", equalTo("BUILD_GENERAL1_SMALL"))
                .body("fleets[0].environmentType", equalTo("ARM_CONTAINER"))
                .body("fleets[0].overflowBehavior", equalTo("ON_DEMAND"))
                .body("fleets[0].status.statusCode", equalTo("ACTIVE"))
                .body("fleets[0].tags[0].key", equalTo("role"))
                .body("fleetsNotFound", empty());
        codeBuild("ListFleets", "{}").body("fleets", hasItem(FLEET));
        codeBuild("BatchGetProjects", "{\"names\":[\"" + PROJECT + "\"]}")
                .body("projects[0].environment.fleet.fleetArn", equalTo(arn));

        cloudFormation("UpdateStack", "2", "LINUX_CONTAINER");
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");

        assertEquals(arn, outputs().get("FleetRef"));
        codeBuild("BatchGetFleets", "{\"names\":[\"" + FLEET + "\"]}")
                .body("fleets.arn", contains(arn))
                .body("fleets[0].baseCapacity", equalTo(2))
                .body("fleets[0].environmentType", equalTo("LINUX_CONTAINER"));

        cloudFormation("DeleteStack", null, null);
        CfnStackWaits.awaitStackDeleted(STACK);

        codeBuild("BatchGetFleets", "{\"names\":[\"" + arn + "\"]}")
                .body("fleets", empty())
                .body("fleetsNotFound", contains(arn));
        codeBuild("ListFleets", "{}").body("fleets", not(hasItem(FLEET)));
    }

    private static void cloudFormation(String action, String capacity, String environmentType) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (capacity != null) {
            request.formParam("TemplateBody", TEMPLATE)
                    .formParam("Parameters.member.1.ParameterKey", "Capacity")
                    .formParam("Parameters.member.1.ParameterValue", capacity)
                    .formParam("Parameters.member.2.ParameterKey", "EnvironmentType")
                    .formParam("Parameters.member.2.ParameterValue", environmentType);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs() {
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse codeBuild(String action, String body) {
        return given()
                .header("X-Amz-Target", "CodeBuild_20161006." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/").then().statusCode(200);
    }
}
