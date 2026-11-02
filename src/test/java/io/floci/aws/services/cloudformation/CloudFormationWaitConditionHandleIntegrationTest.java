package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::CloudFormation::WaitConditionHandle} has no properties, and a {@code Ref} to it returns a URL to signal
 * to, as the CloudFormation Template Reference's page for the type describes. A stack of one handle, the placeholder a
 * stack is first created from before its real template is planned over it as an update, stands, takes that update and
 * goes.
 */
@QuarkusTest
class CloudFormationWaitConditionHandleIntegrationTest {

    private static final String HANDLE = """
            {"Resources":{"Placeholder":{"Type":"AWS::CloudFormation::WaitConditionHandle"}},
             "Outputs":{"Url":{"Value":{"Ref":"Placeholder"}},
                        "Id":{"Value":{"Fn::GetAtt":["Placeholder","Id"]}}}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStackOfOneHandleStandsAndItsRefIsAUrlNamingTheStackAndTheHandle() {
        String stack = "wch-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", "StackName", stack, "TemplateBody", HANDLE,
                "Tags.member.1.Key", "swb:tenant", "Tags.member.1.Value", "t").statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        XmlPath described = describe(stack);
        String url = output(described, "Url");
        assertTrue(url.startsWith("http"), url);
        assertTrue(url.contains("/" + stack + "/Placeholder/"), url);
        assertEquals(url, output(described, "Id"));
        assertEquals(url, cfn("DescribeStackResource", "StackName", stack, "LogicalResourceId", "Placeholder")
                .statusCode(200).extract().xmlPath()
                .getString("DescribeStackResourceResponse.DescribeStackResourceResult.StackResourceDetail"
                        + ".PhysicalResourceId"));
    }

    @Test
    void anUpdateOverTheHandleRemovesItAndTheStackThenGoes() {
        String stack = "wch-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", "StackName", stack, "TemplateBody", HANDLE).statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        cfn("UpdateStack", "StackName", stack,
                "TemplateBody", "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}").statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        List<String> standing = cfn("DescribeStackResources", "StackName", stack).statusCode(200).extract()
                .xmlPath().getList("DescribeStackResourcesResponse.DescribeStackResourcesResult.StackResources"
                        + ".member.LogicalResourceId");
        assertEquals(List.of("Queue"), standing);

        cfn("DeleteStack", "StackName", stack).statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static String output(XmlPath described, String key) {
        return described.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static XmlPath describe(String stack) {
        return cfn("DescribeStacks", "StackName", stack).statusCode(200).extract().xmlPath();
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
