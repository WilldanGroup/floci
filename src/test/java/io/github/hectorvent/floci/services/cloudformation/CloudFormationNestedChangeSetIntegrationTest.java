package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A change set created with {@code IncludeNestedStacks} plans a change set on each nested stack it modifies, as the
 * CloudFormation User Guide's "Change sets for nested stacks" describes: the parent's change to the nested stack
 * names it as its {@code ChangeSetId}; the nested change set holds the nested stack's own changes, names the parent
 * and the root as {@code ParentChangeSetId} and {@code RootChangeSetId}, and is {@code UNAVAILABLE}, "Executable from
 * root change set", so it is executed and deleted from the root alone, which takes it with it.
 */
@QuarkusTest
class CloudFormationNestedChangeSetIntegrationTest {

    private static final String QUEUE = "{\"Type\":\"AWS::SQS::Queue\"}";

    private String bucket;
    private String parent;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void standAParentNestingAChild() {
        String suffix = Long.toString(System.nanoTime(), 36);
        bucket = "nested-cs-" + suffix;
        parent = "nested-cs-parent-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        putTemplate("child-v1.json", "{\"Resources\":{\"One\":" + QUEUE + "}}");
        putTemplate("child-v2.json", "{\"Resources\":{\"One\":" + QUEUE + ",\"Two\":" + QUEUE + "}}");
        cfn("CreateStack", "StackName", parent, "TemplateBody", parentTemplate("child-v1.json")).statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(parent).status());
    }

    @Test
    void theParentsChangeToTheNestedStackNamesTheNestedChangeSetWhichHoldsItsOwnChanges() {
        String root = createRoot("plan");

        XmlPath rootRead = describe("StackName", parent, "ChangeSetName", "plan");
        assertEquals("true", rootRead.getString(path("IncludeNestedStacks")));
        assertEquals("", rootRead.getString(path("ParentChangeSetId")));
        String nested = rootRead.getString(path("Changes.member.find { it.ResourceChange.LogicalResourceId == 'Child' }"
                + ".ResourceChange.ChangeSetId"));
        assertNotNull(nested);
        assertEquals(true, nested.contains(":changeSet/"), nested);

        XmlPath nestedRead = describe("ChangeSetName", nested);
        assertEquals(root, nestedRead.getString(path("ParentChangeSetId")));
        assertEquals(root, nestedRead.getString(path("RootChangeSetId")));
        assertEquals("true", nestedRead.getString(path("IncludeNestedStacks")));
        assertEquals("CREATE_COMPLETE", nestedRead.getString(path("Status")));
        assertEquals("UNAVAILABLE", nestedRead.getString(path("ExecutionStatus")));
        assertEquals("Executable from root change set", nestedRead.getString(path("StatusReason")));
        List<String> changes = nestedRead.getList(path("Changes.member.ResourceChange.LogicalResourceId"));
        assertEquals(List.of("Two"), changes);
        assertEquals("Add", nestedRead.getString(path("Changes.member.ResourceChange.Action")));
    }

    @Test
    void theNestedChangeSetIsExecutedFromTheRootAloneWhichTakesItWithIt() {
        createRoot("execute");
        String nested = describe("StackName", parent, "ChangeSetName", "execute")
                .getString(path("Changes.member.find { it.ResourceChange.LogicalResourceId == 'Child' }"
                        + ".ResourceChange.ChangeSetId"));

        cfn("ExecuteChangeSet", "ChangeSetName", nested).statusCode(400)
                .body(containsString("InvalidChangeSetStatus"));
        cfn("ExecuteChangeSet", "StackName", parent, "ChangeSetName", "execute").statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(parent).status());
        cfn("DescribeChangeSet", "ChangeSetName", nested).statusCode(400)
                .body(containsString("ChangeSetNotFound"));
    }

    @Test
    void deletingTheRootDeletesTheNestedChangeSet() {
        createRoot("delete");
        String nested = describe("StackName", parent, "ChangeSetName", "delete")
                .getString(path("Changes.member.find { it.ResourceChange.LogicalResourceId == 'Child' }"
                        + ".ResourceChange.ChangeSetId"));

        cfn("DeleteChangeSet", "StackName", parent, "ChangeSetName", "delete").statusCode(200);
        cfn("DescribeChangeSet", "ChangeSetName", nested).statusCode(400)
                .body(containsString("ChangeSetNotFound"));
    }

    @Test
    void aChangeSetCreatedWithoutTheOptionPlansNoNestedChangeSet() {
        cfn("CreateChangeSet", "StackName", parent, "ChangeSetName", "flat",
                "TemplateBody", parentTemplate("child-v2.json")).statusCode(200);
        awaitCreated("flat");

        XmlPath rootRead = describe("StackName", parent, "ChangeSetName", "flat");
        assertEquals("false", rootRead.getString(path("IncludeNestedStacks")));
        assertEquals("", rootRead.getString(path("Changes.member.find { it.ResourceChange.LogicalResourceId == 'Child' }"
                + ".ResourceChange.ChangeSetId")));
    }

    /** A root change set moving the child to its second template: its ARN, once created. */
    private String createRoot(String name) {
        String id = cfn("CreateChangeSet", "StackName", parent, "ChangeSetName", name,
                "TemplateBody", parentTemplate("child-v2.json"), "IncludeNestedStacks", "true").statusCode(200)
                .extract().xmlPath().getString("CreateChangeSetResponse.CreateChangeSetResult.Id");
        awaitCreated(name);
        return id;
    }

    private void awaitCreated(String name) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = describe("StackName", parent, "ChangeSetName", name).getString(path("Status"));
            if ("CREATE_COMPLETE".equals(status) || "FAILED".equals(status)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String parentTemplate(String childKey) {
        return """
                {"Resources":{"Child":{"Type":"AWS::CloudFormation::Stack",
                  "Properties":{"TemplateURL":"http://localhost/%s/%s"}}}}""".formatted(bucket, childKey);
    }

    private void putTemplate(String key, String body) {
        given().contentType("application/json").body(body).when().put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private static String path(String member) {
        return "DescribeChangeSetResponse.DescribeChangeSetResult." + member;
    }

    private static XmlPath describe(String... params) {
        return cfn("DescribeChangeSet", params).statusCode(200).extract().xmlPath();
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
