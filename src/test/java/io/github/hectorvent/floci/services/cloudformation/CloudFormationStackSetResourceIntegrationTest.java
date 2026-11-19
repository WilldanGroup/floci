package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::CloudFormation::StackSet} as the CloudFormation Template Reference's page for the type describes it: a
 * StackSet created from its {@code TemplateBody} and {@code Parameters}, with an instance in each account and Region
 * of its {@code StackInstancesGroup}, whose {@code Ref} and {@code StackSetId} are the StackSet's id; an update that
 * takes new values and Regions; a delete that removes every instance and the StackSet. A Region whose instance fails
 * within the {@code OperationPreferences}' {@code FailureToleranceCount} leaves the stack standing and the instance
 * {@code OUTDATED} with the failed resource's reason, and one past it fails the stack with the operation's reasons, as
 * a real account answered (us-west-2, 2026-11-19).
 */
@QuarkusTest
class CloudFormationStackSetResourceIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String HOME = "us-east-1";

    /**
     * The instances' template: a queue named from a parameter, and, in the Region {@code FailRegion} names alone, a
     * nested stack with no {@code TemplateURL}, which fails and rolls the instance's stack back.
     */
    private static final String INSTANCE = """
            {"Parameters":{"QueueName":{"Type":"String"},"FailRegion":{"Type":"String"}},
             "Conditions":{"Fails":{"Fn::Equals":[{"Ref":"AWS::Region"},{"Ref":"FailRegion"}]}},
             "Resources":{
               "Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":{"Ref":"QueueName"}}},
               "Broken":{"Type":"AWS::CloudFormation::Stack","Condition":"Fails","DependsOn":"Queue",
                         "Properties":{}}}}""";

    private static String parent(String stackSetName) {
        return """
            {"Parameters":{"QueueName":{"Type":"String"},"TargetRegions":{"Type":"CommaDelimitedList"},
                           "FailRegion":{"Type":"String","Default":"none"},
                           "Tolerance":{"Type":"Number","Default":"0"},
                           "BreakAfter":{"Type":"String","Default":"no"}},
             "Conditions":{"Breaks":{"Fn::Equals":[{"Ref":"BreakAfter"},"yes"]}},
             "Resources":{
               "After":{"Type":"AWS::CloudFormation::Stack","Condition":"Breaks","DependsOn":"Set","Properties":{}},
               "Set":{"Type":"AWS::CloudFormation::StackSet","Properties":{
               "StackSetName":"%s",
               "Description":"The test's StackSet.",
               "PermissionModel":"SELF_MANAGED",
               "OperationPreferences":{"RegionConcurrencyType":"PARALLEL",
                                       "FailureToleranceCount":{"Ref":"Tolerance"},"MaxConcurrentCount":1},
               "StackInstancesGroup":[{"DeploymentTargets":{"Accounts":[{"Ref":"AWS::AccountId"}]},
                                       "Regions":{"Ref":"TargetRegions"}}],
               "Parameters":[{"ParameterKey":"QueueName","ParameterValue":{"Ref":"QueueName"}},
                             {"ParameterKey":"FailRegion","ParameterValue":{"Ref":"FailRegion"}}],
               "TemplateBody":%s}}},
             "Outputs":{"Ref":{"Value":{"Ref":"Set"}},"Id":{"Value":{"Fn::GetAtt":["Set","StackSetId"]}}}}"""
                .formatted(stackSetName, quoted(INSTANCE));
    }

    @Test
    void aStackSetStandsWithAnInstanceInEachRegionAndItsRefIsItsId() {
        String name = unique("ssr");
        createStack(name, name + "-q", "us-east-1,us-west-2", "none", "0");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        XmlPath described = cfn("DescribeStacks", "StackName", name).statusCode(200).extract().xmlPath();
        String stackSetId = cfn("DescribeStackSet", "StackSetName", name).statusCode(200).extract().xmlPath()
                .getString("DescribeStackSetResponse.DescribeStackSetResult.StackSet.StackSetId");
        assertTrue(stackSetId.startsWith(name + ":"), stackSetId);
        assertEquals(stackSetId, output(described, "Ref"));
        assertEquals(stackSetId, output(described, "Id"));

        assertEquals(Map.of("us-east-1", "CURRENT/SUCCEEDED", "us-west-2", "CURRENT/SUCCEEDED"), instances(name));
        queueUrl("us-east-1", name + "-q").statusCode(200);
        queueUrl("us-west-2", name + "-q").statusCode(200);
    }

    @Test
    void anUpdateTakesTheNewValuesAndRegionsAndADeleteRemovesEverything() {
        String name = unique("ssu");
        createStack(name, name + "-a", "us-east-1,us-west-2", "none", "0");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        cfn("UpdateStack", "StackName", name, "TemplateBody", parent(name),
                "Parameters.member.1.ParameterKey", "QueueName", "Parameters.member.1.ParameterValue", name + "-b",
                "Parameters.member.2.ParameterKey", "TargetRegions",
                "Parameters.member.2.ParameterValue", "us-west-2,eu-west-1").statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        assertEquals(Map.of("us-west-2", "CURRENT/SUCCEEDED", "eu-west-1", "CURRENT/SUCCEEDED"), instances(name));
        queueUrl("us-west-2", name + "-b").statusCode(200);
        queueUrl("eu-west-1", name + "-b").statusCode(200);
        queueUrl("us-west-2", name + "-a").statusCode(400);
        queueUrl("us-east-1", name + "-a").statusCode(400);

        cfn("DeleteStack", "StackName", name).statusCode(200);
        CfnStackWaits.awaitStackDeleted(name, auth(HOME, "cloudformation"));
        cfn("DescribeStackSet", "StackSetName", name).statusCode(404);
        queueUrl("us-west-2", name + "-b").statusCode(400);
        queueUrl("eu-west-1", name + "-b").statusCode(400);
    }

    @Test
    void anUpdateALaterResourceFailsPutsTheStackSetBack() {
        String name = unique("ssr");
        createStack(name, name + "-a", "us-east-1", "none", "0");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        cfn("UpdateStack", "StackName", name, "TemplateBody", parent(name),
                "Parameters.member.1.ParameterKey", "QueueName", "Parameters.member.1.ParameterValue", name + "-b",
                "Parameters.member.2.ParameterKey", "TargetRegions", "Parameters.member.2.ParameterValue", "us-east-1",
                "Parameters.member.3.ParameterKey", "BreakAfter", "Parameters.member.3.ParameterValue", "yes")
                .statusCode(200);
        assertEquals("UPDATE_ROLLBACK_COMPLETE",
                CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        assertEquals(Map.of("us-east-1", "CURRENT/SUCCEEDED"), instances(name));
        queueUrl("us-east-1", name + "-a").statusCode(200);
        queueUrl("us-east-1", name + "-b").statusCode(400);
    }

    @Test
    void aRegionThatFailsWithinTheToleranceLeavesTheStackAndTheInstanceOutdated() {
        String name = unique("sst");
        createStack(name, name + "-q", "us-east-1,us-west-2", "us-west-2", "1");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        assertEquals(Map.of("us-east-1", "CURRENT/SUCCEEDED", "us-west-2", "OUTDATED/FAILED"), instances(name));
        assertEquals("ResourceLogicalId:Broken, ResourceType:AWS::CloudFormation::Stack, "
                        + "ResourceStatusReason:Missing TemplateURL",
                cfn("DescribeStackInstance", "StackSetName", name, "StackInstanceAccount", ACCOUNT,
                        "StackInstanceRegion", "us-west-2").statusCode(200).extract().xmlPath()
                        .getString("DescribeStackInstanceResponse.DescribeStackInstanceResult.StackInstance"
                                + ".StatusReason"));

        cfn("DeleteStack", "StackName", name).statusCode(200);
        CfnStackWaits.awaitStackDeleted(name, auth(HOME, "cloudformation"));
        cfn("DescribeStackSet", "StackSetName", name).statusCode(404);
    }

    @Test
    void aRegionThatFailsPastTheToleranceFailsTheStackAndLeavesNoStackSet() {
        String name = unique("ssf");
        createStack(name, name + "-q", "us-east-1,us-west-2", "us-west-2", "0");
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());

        String reason = CfnStackWaits.failedResourceReason(name, auth(HOME, "cloudformation"));
        assertTrue(reason.startsWith("Stack set operation ["), reason);
        assertTrue(reason.endsWith("] was unexpectedly stopped or failed. status reason(s): [ResourceLogicalId:Broken,"
                + " ResourceType:AWS::CloudFormation::Stack, ResourceStatusReason:Missing TemplateURL]"), reason);
        cfn("DescribeStackSet", "StackSetName", name).statusCode(404);
        queueUrl("us-east-1", name + "-q").statusCode(400);
    }

    @Test
    void aStackSetNamingBothTemplateBodyAndTemplateUrlFails() {
        String name = unique("ssb");
        String template = """
            {"Resources":{"Set":{"Type":"AWS::CloudFormation::StackSet","Properties":{
               "StackSetName":"%s","PermissionModel":"SELF_MANAGED",
               "TemplateBody":%s,"TemplateURL":"https://s3.amazonaws.com/nowhere/t.json"}}}}"""
                .formatted(name, quoted(INSTANCE));
        cfn("CreateStack", "StackName", name, "TemplateBody", template).statusCode(200);
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(HOME, "cloudformation")).status());
        assertTrue(CfnStackWaits.failedResourceReason(name, auth(HOME, "cloudformation"))
                .contains("you must include either TemplateURL or TemplateBody in a StackSet, but you can't use both"));
    }

    @Test
    void createStackInstancesWithinItsFailureToleranceSucceeds() {
        String name = unique("ssa");
        cfn("CreateStackSet", "StackSetName", name, "TemplateBody", INSTANCE,
                "Parameters.member.1.ParameterKey", "QueueName", "Parameters.member.1.ParameterValue", name,
                "Parameters.member.2.ParameterKey", "FailRegion", "Parameters.member.2.ParameterValue", "us-west-2")
                .statusCode(200);
        String operationId = cfn("CreateStackInstances", "StackSetName", name, "Accounts.member.1", ACCOUNT,
                "Regions.member.1", "us-east-1", "Regions.member.2", "us-west-2",
                "OperationPreferences.FailureToleranceCount", "1").statusCode(200).extract().xmlPath()
                .getString("CreateStackInstancesResponse.CreateStackInstancesResult.OperationId");

        assertEquals("SUCCEEDED", cfn("DescribeStackSetOperation", "StackSetName", name, "OperationId", operationId)
                .statusCode(200).extract().xmlPath()
                .getString("DescribeStackSetOperationResponse.DescribeStackSetOperationResult.StackSetOperation"
                        + ".Status"));
        assertEquals(Map.of("us-east-1", "CURRENT/SUCCEEDED", "us-west-2", "OUTDATED/FAILED"), instances(name));
    }

    private static void createStack(String name, String queueName, String regions, String failRegion,
                                    String tolerance) {
        cfn("CreateStack", "StackName", name, "TemplateBody", parent(name),
                "Parameters.member.1.ParameterKey", "QueueName", "Parameters.member.1.ParameterValue", queueName,
                "Parameters.member.2.ParameterKey", "TargetRegions", "Parameters.member.2.ParameterValue", regions,
                "Parameters.member.3.ParameterKey", "FailRegion", "Parameters.member.3.ParameterValue", failRegion,
                "Parameters.member.4.ParameterKey", "Tolerance", "Parameters.member.4.ParameterValue", tolerance)
                .statusCode(200);
    }

    /** Each instance's Region and its status and detailed status, as ListStackInstances answers them. */
    private static Map<String, String> instances(String stackSetName) {
        XmlPath listed = cfn("ListStackInstances", "StackSetName", stackSetName).statusCode(200).extract().xmlPath();
        String path = "ListStackInstancesResponse.ListStackInstancesResult.Summaries.member";
        List<String> regions = listed.getList(path + ".Region");
        List<String> statuses = listed.getList(path + ".Status");
        List<String> detailed = listed.getList(path + ".StackInstanceStatus.DetailedStatus");
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < regions.size(); i++) {
            out.put(regions.get(i), statuses.get(i) + "/" + detailed.get(i));
        }
        return out;
    }

    private static String output(XmlPath described, String key) {
        return described.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static ValidatableResponse queueUrl(String region, String queueName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl").formParam("QueueName", queueName)
                .header("Authorization", auth(region, "sqs"))
                .post("/").then();
    }

    private static ValidatableResponse cfn(String action, String... params) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .header("Authorization", auth(HOME, "cloudformation"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/").then();
    }

    private static String auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260215/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    /** A template as a JSON string literal, the shape {@code TemplateBody} takes inside another template. */
    private static String quoted(String template) {
        return "\"" + template.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
