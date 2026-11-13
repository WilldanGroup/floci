package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DescribeStacks and ListStacks report a nested stack with its ParentId, the stack that nests it, and its
 * RootId, the top of its nesting, as AWS does, so a reader tells a root from its children and a child from a
 * grandchild by them; a root stack carries neither.
 */
@QuarkusTest
class CloudFormationNestedStackIdsIntegrationTest {

    @Test
    void aNestedStackReportsItsParentAndItsRootAndARootStackNeither() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackName = "cfn-nested-ids-" + suffix;
        String bucket = "cfn-nested-ids-templates-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        uploadTemplate(bucket, "grandchild.json", "{\"Description\":\"grandchild\",\"Resources\":{}}");
        uploadTemplate(bucket, "child.json", """
                {"Resources":{"GrandchildStack":{"Type":"AWS::CloudFormation::Stack",
                  "Properties":{"TemplateURL":"http://localhost/%1$s/grandchild.json"}}}}
                """.formatted(bucket));
        cfn("CreateStack", stackName).formParam("TemplateBody", """
                {"Resources":{"ChildStack":{"Type":"AWS::CloudFormation::Stack",
                  "Properties":{"TemplateURL":"http://localhost/%1$s/child.json"}}}}
                """.formatted(bucket)).when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

        String rootId = XmlParser.extractFirst(describe(stackName), "StackId", null);
        String childId = nestedStackId(stackName, "ChildStack");
        String grandchildId = nestedStackId(childId, "GrandchildStack");

        String root = describe(stackName);
        assertNull(XmlParser.extractFirst(root, "ParentId", null));
        assertNull(XmlParser.extractFirst(root, "RootId", null));
        String child = describe(childId);
        assertEquals(rootId, XmlParser.extractFirst(child, "ParentId", null));
        assertEquals(rootId, XmlParser.extractFirst(child, "RootId", null));
        String grandchild = describe(grandchildId);
        assertEquals(childId, XmlParser.extractFirst(grandchild, "ParentId", null));
        assertEquals(rootId, XmlParser.extractFirst(grandchild, "RootId", null));

        XmlPath summaries = cfn("ListStacks", null).when().post("/").then().statusCode(200).extract().xmlPath();
        assertEquals("", summary(summaries, rootId, "ParentId"));
        assertEquals("", summary(summaries, rootId, "RootId"));
        assertEquals(rootId, summary(summaries, childId, "ParentId"));
        assertEquals(rootId, summary(summaries, childId, "RootId"));
        assertEquals(childId, summary(summaries, grandchildId, "ParentId"));
        assertEquals(rootId, summary(summaries, grandchildId, "RootId"));

        cfn("DeleteStack", stackName).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static String describe(String stackName) {
        return cfn("DescribeStacks", stackName).when().post("/").then().statusCode(200).extract().asString();
    }

    /** The element of the summary whose StackId is {@code stackId}; empty when the summary carries none. */
    private static String summary(XmlPath summaries, String stackId, String element) {
        return summaries.getString("**.find { it.name() == 'member' && it.StackId.text() == '" + stackId + "' }."
                + element);
    }

    private static String nestedStackId(String stackName, String logicalId) {
        String xml = cfn("DescribeStackResources", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "LogicalResourceId", "PhysicalResourceId").get(logicalId);
    }

    private static void uploadTemplate(String bucket, String key, String body) {
        given().contentType("application/json").body(body)
                .when().put("/" + bucket + "/" + key).then().statusCode(200);
    }
}
