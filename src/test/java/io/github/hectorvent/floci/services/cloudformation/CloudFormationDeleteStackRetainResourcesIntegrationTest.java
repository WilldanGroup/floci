package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DeleteStack's {@code RetainResources}: "For stacks in the DELETE_FAILED state, a list of resource logical IDs that
 * are associated with the resources you want to retain. During deletion, CloudFormation deletes the stack but doesn't
 * delete the retained resources" (CloudFormation API Reference, DeleteStack), as for a bucket that is not empty.
 */
@QuarkusTest
class CloudFormationDeleteStackRetainResourcesIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStackWhoseDeleteFailedIsDeletedKeepingTheResourcesItNames() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "retain-" + suffix;
        String bucket = "retain-bucket-" + suffix;
        cfn("CreateStack", "StackName", stack, "TemplateBody", """
                {"Resources":{
                  "Bucket":{"Type":"AWS::S3::Bucket","Properties":{"BucketName":"%s"}},
                  "Queue":{"Type":"AWS::SQS::Queue"}}}""".formatted(bucket)).statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        given().body("held").when().put("/" + bucket + "/held.txt").then().statusCode(200);

        cfn("DeleteStack", "StackName", stack).statusCode(200);
        assertEquals("DELETE_FAILED", CfnStackWaits.awaitTerminal(stack).status());

        cfn("DeleteStack", "StackName", stack, "RetainResources.member.1", "Bucket").statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
        given().when().head("/" + bucket).then().statusCode(200);
        cfn("DescribeStacks", "StackName", stack).statusCode(400).body(containsString("does not exist"));
    }

    @Test
    void aDeleteNamingResourcesToRetainOfAStackNotDeleteFailedDeletesEveryResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "retain-standing-" + suffix;
        String bucket = "retain-standing-bucket-" + suffix;
        cfn("CreateStack", "StackName", stack, "TemplateBody", """
                {"Resources":{"Bucket":{"Type":"AWS::S3::Bucket","Properties":{"BucketName":"%s"}}}}"""
                .formatted(bucket)).statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        cfn("DeleteStack", "StackName", stack, "RetainResources.member.1", "Bucket").statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
        given().when().head("/" + bucket).then().statusCode(404);
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
