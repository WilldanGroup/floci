package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A nested stack carries the stack-level tags of the stack that nests it, at every depth, with the
 * {@code AWS::CloudFormation::Stack} resource's own {@code Tags} added over them. CloudFormation API Reference,
 * CreateStack, Tags: "CloudFormation also propagates these tags to the resources created in the stack"; a nested
 * stack is such a resource, and CloudFormation Template Reference, AWS::CloudFormation::Stack, Tags, gives it tags
 * of its own. A policy that admits a stack by {@code aws:ResourceTag} admits its nested stacks as on AWS.
 */
@QuarkusTest
class CloudFormationNestedStackTagsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261112/us-east-1/cloudformation/aws4_request";

    private static final String GRANDCHILD_TEMPLATE = """
        {
          "Resources": {
            "Topic": {"Type": "AWS::SNS::Topic"}
          }
        }""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aNestedStackCarriesItsParentsStackLevelTagsAndItsOwnAtEveryDepth() {
        String root = "nested-tags-" + Long.toString(System.nanoTime(), 36);
        String bucket = root + "-templates";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(GRANDCHILD_TEMPLATE)
                .when().put("/" + bucket + "/grandchild.json").then().statusCode(200);
        given().contentType("application/json").body(nesting("Grandchild", bucket, "grandchild.json", null))
                .when().put("/" + bucket + "/child.json").then().statusCode(200);
        try {
            request("CreateStack", "StackName", root,
                    "TemplateBody", nesting("Child", bucket, "child.json", "{\"Key\":\"layer\",\"Value\":\"child\"}"),
                    "Tags.member.1.Key", "swb:tenant", "Tags.member.1.Value", "platform",
                    "Tags.member.2.Key", "layer", "Tags.member.2.Value", "root")
                    .statusCode(200);
            CfnStackWaits.awaitStatus(root, "CREATE_COMPLETE");

            String child = nestedStackId(root, "Child");
            String grandchild = nestedStackId(child, "Grandchild");

            assertEquals(Map.of("swb:tenant", "platform", "layer", "root"), tags(root),
                    "the root carries the tags CreateStack gave it");
            assertEquals(Map.of("swb:tenant", "platform", "layer", "child"), tags(child),
                    "the child carries the root's tags, its own Tags over them");
            assertEquals(Map.of("swb:tenant", "platform", "layer", "child"), tags(grandchild),
                    "the grandchild carries the child's tags, the root's among them");
        } finally {
            if (request("DescribeStacks", "StackName", root).extract().statusCode() == 200) {
                request("DeleteStack", "StackName", root);
                CfnStackWaits.awaitStackDeleted(root, CFN_AUTH);
            }
            given().when().delete("/" + bucket + "/child.json");
            given().when().delete("/" + bucket + "/grandchild.json");
            given().when().delete("/" + bucket);
        }
    }

    /** A template nesting the template at {@code key} of {@code bucket} as {@code logicalId}, with {@code tag}. */
    private static String nesting(String logicalId, String bucket, String key, String tag) {
        String tags = tag == null ? "" : ", \"Tags\": [" + tag + "]";
        return """
            {
              "Resources": {
                "%1$s": {"Type": "AWS::CloudFormation::Stack",
                         "Properties": {"TemplateURL": "http://localhost/%2$s/%3$s"%4$s}}
              }
            }""".formatted(logicalId, bucket, key, tags);
    }

    /** The stack ID of the nested stack {@code logicalId} of {@code stack}, its physical ID. */
    private static String nestedStackId(String stack, String logicalId) {
        String id = XmlParser.extractFirst(request("DescribeStackResource", "StackName", stack,
                "LogicalResourceId", logicalId).statusCode(200).extract().asString(), "PhysicalResourceId", null);
        assertNotNull(id, "the nested stack " + logicalId + " of " + stack + " has a physical ID");
        return id;
    }

    private static Map<String, String> tags(String stack) {
        String xml = request("DescribeStacks", "StackName", stack).statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

    private static ValidatableResponse request(String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParams(parameters).when().post("/").then().log().ifValidationFails();
    }
}
