package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnStackWaits.StackState;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@code AWS::CloudFormation::WaitCondition} holds its stack's create until its handle's URL has received the
 * success signals its {@code Count} asks for, fails it on a failure signal, and fails it when its {@code Timeout}
 * passes, each signal the JSON a {@code PUT} to the URL carries (CloudFormation User Guide, Create wait conditions
 * in a CloudFormation template). The signals are sent the way that page's {@code curl} examples send them, while
 * the stack's create, started by ExecuteChangeSet, runs.
 */
@QuarkusTest
class CloudFormationWaitConditionIntegrationTest {

    /** The path of every handle's URL. */
    private static final String SIGNAL_PATH = "/cloudformation-waitcondition";

    private static final String TEMPLATE = """
            {"Parameters": {"Count": {"Type": "Number"}, "Timeout": {"Type": "String"}},
             "Resources": {
               "Handle": {"Type": "AWS::CloudFormation::WaitConditionHandle"},
               "Condition": {"Type": "AWS::CloudFormation::WaitCondition",
                             "Properties": {"Handle": {"Ref": "Handle"}, "Count": {"Ref": "Count"},
                                            "Timeout": {"Ref": "Timeout"}}},
               "After": {"Type": "AWS::SQS::Queue", "DependsOn": "Condition"}},
             "Outputs": {"Data": {"Value": {"Fn::GetAtt": ["Condition", "Data"]}},
                         "Id": {"Value": {"Fn::GetAtt": ["Condition", "Id"]}},
                         "Ref": {"Value": {"Ref": "Condition"}}}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theStackWaitsForCountDistinctSuccessSignalsAndDataCarriesThem() {
        String stack = stackName();
        create(stack, "2", "30");
        String handle = handlePath(stack);

        signal(handle, "{\"Status\":\"SUCCESS\",\"Reason\":\"one\",\"UniqueId\":\"ID1\",\"Data\":\"first\"}")
                .statusCode(200);
        // The same UniqueId again is a retransmission, and does not make a second signal.
        signal(handle, "{\"Status\":\"SUCCESS\",\"Reason\":\"again\",\"UniqueId\":\"ID1\",\"Data\":\"again\"}")
                .statusCode(200);
        assertEquals("CREATE_IN_PROGRESS", status(stack));
        // The queue that depends on the condition has not been reached.
        assertThat(standing(stack), not(hasItem("After")));

        signal(handle, "{\"Status\":\"SUCCESS\",\"Reason\":\"two\",\"UniqueId\":\"ID2\",\"Data\":\"second\"}")
                .statusCode(200);
        StackState done = CfnStackWaits.awaitTerminal(stack);
        assertEquals("CREATE_COMPLETE", done.status(), done.reason());

        XmlPath described = cfn("DescribeStacks", "StackName", stack).statusCode(200).extract().xmlPath();
        assertEquals("{\"ID1\":\"first\",\"ID2\":\"second\"}", output(described, "Data"));
        String id = output(described, "Id");
        assertThat(id, containsString(stack));
        assertEquals(id, output(described, "Ref"));

        cfn("DeleteStack", "StackName", stack).statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void aFailureSignalFailsTheConditionWithItsReasonAndTheStackRollsBack() {
        String stack = stackName();
        create(stack, "1", "30");
        String handle = handlePath(stack);

        signal(handle, "{\"Status\":\"FAILURE\",\"Reason\":\"configuration broke\",\"UniqueId\":\"ID9\","
                + "\"Data\":\"x\"}").statusCode(200);

        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("WaitCondition received failed message: 'configuration broke' for uniqueId: ID9",
                CfnStackWaits.failedResourceReason(stack));
        cfn("DeleteStack", "StackName", stack).statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void theConditionFailsWhenItsTimeoutPassesFirst() {
        String stack = stackName();
        create(stack, "2", "1");
        signal(handlePath(stack), "{\"Status\":\"SUCCESS\",\"UniqueId\":\"only\",\"Data\":\"one\"}").statusCode(200);

        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("WaitCondition timed out. Received 1 conditions when expecting 2",
                CfnStackWaits.failedResourceReason(stack));
        cfn("DeleteStack", "StackName", stack).statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    @Test
    void aBodyThatIsNoSignalIsRefused() {
        signal("us-east-1/000000000000/no-stack/NoHandle/0", "{\"Status\":\"DONE\"}").statusCode(400);
        signal("us-east-1/000000000000/no-stack/NoHandle/0", "not json").statusCode(400);
    }

    private static String stackName() {
        return "wc-" + Long.toString(System.nanoTime(), 36);
    }

    /** Creates the stack through a change set, as a deploy does; ExecuteChangeSet answers before the create ends. */
    private static void create(String stack, String count, String timeout) {
        cfn("CreateChangeSet", "StackName", stack, "ChangeSetName", "create", "ChangeSetType", "CREATE",
                "TemplateBody", TEMPLATE,
                "Parameters.member.1.ParameterKey", "Count", "Parameters.member.1.ParameterValue", count,
                "Parameters.member.2.ParameterKey", "Timeout", "Parameters.member.2.ParameterValue", timeout)
                .statusCode(200);
        cfn("ExecuteChangeSet", "StackName", stack, "ChangeSetName", "create").statusCode(200);
    }

    /** The handle's URL once the stack has made it, as the path after the signal prefix. */
    private static String handlePath(String stack) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String url = cfn("DescribeStackResources", "StackName", stack).statusCode(200).extract().xmlPath()
                    .getString("DescribeStackResourcesResponse.DescribeStackResourcesResult.StackResources.member"
                            + ".find { it.LogicalResourceId == 'Handle' }.PhysicalResourceId");
            if (url != null && url.contains(SIGNAL_PATH + "/")) {
                String path = URI.create(url).getRawPath();
                return path.substring(path.indexOf(SIGNAL_PATH)
                        + SIGNAL_PATH.length() + 1);
            }
            sleep();
        }
        fail("Stack " + stack + " made no wait condition handle within ten seconds");
        return null;
    }

    private static ValidatableResponse signal(String handle, String body) {
        return given().body(body).when().put(SIGNAL_PATH + "/" + handle).then();
    }

    private static String status(String stack) {
        return cfn("DescribeStacks", "StackName", stack).statusCode(200).extract().xmlPath()
                .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus");
    }

    private static List<String> standing(String stack) {
        return cfn("DescribeStackResources", "StackName", stack).statusCode(200).extract().xmlPath()
                .getList("DescribeStackResourcesResponse.DescribeStackResourcesResult.StackResources.member"
                        + ".LogicalResourceId");
    }

    private static String output(XmlPath described, String key) {
        return described.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the handle", e);
        }
    }

    private static ValidatableResponse cfn(String action, String... params) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/").then();
    }
}
