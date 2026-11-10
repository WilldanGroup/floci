package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A nested stack names the stack that nests it and the top-level stack it belongs to, in ListStacks and in
 * DescribeStacks alike, and a root stack names neither. CloudFormation API Reference, StackSummary and Stack:
 * ParentId is "the stack ID of the direct parent of this stack. For the first level of nested stacks, the root
 * stack is also the parent stack"; RootId is "the stack ID of the top-level stack to which the nested stack
 * ultimately belongs". A reader that tells root stacks from nested ones by ParentId reads them as on AWS.
 */
@QuarkusTest
class CloudFormationNestedStackParentAndRootIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/cloudformation/aws4_request";

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
    void aNestedStackNamesItsParentAndItsRootAtEveryDepthAndARootNamesNeither() {
        String root = "nested-parent-root-" + Long.toString(System.nanoTime(), 36);
        String bucket = root + "-templates";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(GRANDCHILD_TEMPLATE)
                .when().put("/" + bucket + "/grandchild.json").then().statusCode(200);
        given().contentType("application/json").body(nesting("Grandchild", bucket, "grandchild.json"))
                .when().put("/" + bucket + "/child.json").then().statusCode(200);
        try {
            request("CreateStack", "StackName", root, "TemplateBody", nesting("Child", bucket, "child.json"))
                    .statusCode(200);
            CfnStackWaits.awaitStatus(root, "CREATE_COMPLETE");

            String rootId = XmlParser.extractFirst(describe(root), "StackId", null);
            String childId = nestedStackId(root, "Child");
            String grandchildId = nestedStackId(childId, "Grandchild");
            assertNotNull(rootId);

            List<Map<String, String>> listed = XmlParser.extractGroups(
                    request("ListStacks").statusCode(200).extract().asString(), "member");
            Map<String, String> rootSummary = summaryOf(listed, rootId);
            Map<String, String> childSummary = summaryOf(listed, childId);
            Map<String, String> grandchildSummary = summaryOf(listed, grandchildId);

            assertFalse(rootSummary.containsKey("ParentId"), "the root's summary names no ParentId: " + rootSummary);
            assertFalse(rootSummary.containsKey("RootId"), "the root's summary names no RootId: " + rootSummary);
            assertEquals(rootId, childSummary.get("ParentId"), "the child's summary names the root as its ParentId");
            assertEquals(rootId, childSummary.get("RootId"), "the child's summary names the root as its RootId");
            assertEquals(childId, grandchildSummary.get("ParentId"),
                    "the grandchild's summary names the child as its ParentId");
            assertEquals(rootId, grandchildSummary.get("RootId"),
                    "the grandchild's summary names the root as its RootId");

            String rootDescribed = describe(root);
            assertThat(rootDescribed, not(containsString("<ParentId>")));
            assertThat(rootDescribed, not(containsString("<RootId>")));
            String childDescribed = describe(childSummary.get("StackName"));
            assertEquals(childId, XmlParser.extractFirst(childDescribed, "StackId", null));
            assertEquals(rootId, XmlParser.extractFirst(childDescribed, "ParentId", null),
                    "the child, described by name, names the root as its ParentId");
            assertEquals(rootId, XmlParser.extractFirst(childDescribed, "RootId", null),
                    "the child, described by name, names the root as its RootId");
            String grandchildDescribed = describe(grandchildSummary.get("StackName"));
            assertEquals(grandchildId, XmlParser.extractFirst(grandchildDescribed, "StackId", null));
            assertEquals(childId, XmlParser.extractFirst(grandchildDescribed, "ParentId", null),
                    "the grandchild, described by name, names the child as its ParentId");
            assertEquals(rootId, XmlParser.extractFirst(grandchildDescribed, "RootId", null),
                    "the grandchild, described by name, names the root as its RootId");
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

    /** A template nesting the template at {@code key} of {@code bucket} as {@code logicalId}. */
    private static String nesting(String logicalId, String bucket, String key) {
        return """
            {
              "Resources": {
                "%1$s": {"Type": "AWS::CloudFormation::Stack",
                         "Properties": {"TemplateURL": "http://localhost/%2$s/%3$s"}}
              }
            }""".formatted(logicalId, bucket, key);
    }

    /** The stack ID of the nested stack {@code logicalId} of {@code stack}, its physical ID. */
    private static String nestedStackId(String stack, String logicalId) {
        String id = XmlParser.extractFirst(request("DescribeStackResource", "StackName", stack,
                "LogicalResourceId", logicalId).statusCode(200).extract().asString(), "PhysicalResourceId", null);
        assertNotNull(id, "the nested stack " + logicalId + " of " + stack + " has a physical ID");
        return id;
    }

    private static Map<String, String> summaryOf(List<Map<String, String>> listed, String stackId) {
        return listed.stream().filter(summary -> stackId.equals(summary.get("StackId"))).findFirst()
                .orElseThrow(() -> new AssertionError("ListStacks lists no stack " + stackId + ": " + listed));
    }

    private static String describe(String stack) {
        return request("DescribeStacks", "StackName", stack).statusCode(200).extract().asString();
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
