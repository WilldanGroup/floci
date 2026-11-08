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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CreateStack} over a root stack a CREATE change set left in {@code REVIEW_IN_PROGRESS}, with no operation
 * running on it, takes that root as CloudFormation does: the stack keeps its stack ID, carries the tags the request
 * gives, and the unexecuted change set it held is cleared. CloudFormation API
 * Reference, CreateChangeSet ("the stack is in the REVIEW_IN_PROGRESS state until you execute the change set") and
 * CreateStack; read on an account, a root holding an unexecuted CREATE change set and a bare root alike. A stack
 * another {@code CreateStack} has just made is still a name in use.
 */
@QuarkusTest
class CloudFormationCreateStackOverReviewRootIntegrationTest {

    private static final String QUEUE_TEMPLATE = "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";

    private String name;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void name() {
        name = "review-root-" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void createStackTakesARootHoldingAnUnexecutedCreateChangeSetKeepingItsIdAndClearingTheChangeSet() {
        String rootId = createChangeSet(name, "an-old-create", QUEUE_TEMPLATE);
        assertEquals("REVIEW_IN_PROGRESS", status(name));

        String takenId = cfn("CreateStack", "StackName", name, "TemplateBody", QUEUE_TEMPLATE,
                "Tags.member.1.Key", "team", "Tags.member.1.Value", "platform").statusCode(200)
                .extract().xmlPath().getString("CreateStackResponse.CreateStackResult.StackId");

        assertEquals(rootId, takenId);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        XmlPath stack = describeStack(name);
        assertEquals(rootId, stack.getString(stackPath("StackId")));
        assertEquals("platform", stack.getString(stackPath("Tags.member.find { it.Key == 'team' }.Value")));
        assertEquals(List.of("Queue"), resources(name));
        cfn("DescribeChangeSet", "StackName", name, "ChangeSetName", "an-old-create").statusCode(400)
                .body(containsString("ChangeSetNotFound"));
        assertTrue(!changeSetNames(name).contains("an-old-create"), changeSetNames(name).toString());
    }

    @Test
    void createStackTakesABareRootWhoseCreateChangeSetWasDeleted() {
        String rootId = createChangeSet(name, "an-old-create", QUEUE_TEMPLATE);
        cfn("DeleteChangeSet", "StackName", name, "ChangeSetName", "an-old-create").statusCode(200);
        assertEquals("REVIEW_IN_PROGRESS", status(name));
        assertEquals(List.of(), changeSetNames(name));

        String takenId = cfn("CreateStack", "StackName", name, "TemplateBody", QUEUE_TEMPLATE).statusCode(200)
                .extract().xmlPath().getString("CreateStackResponse.CreateStackResult.StackId");

        assertEquals(rootId, takenId);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        assertEquals(List.of("Queue"), resources(name));
    }

    @Test
    void createStackStillRefusesAStackThatLeftReview() {
        createChangeSet(name, "an-old-create", QUEUE_TEMPLATE);
        cfn("ExecuteChangeSet", "StackName", name, "ChangeSetName", "an-old-create").statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        cfn("CreateStack", "StackName", name, "TemplateBody", QUEUE_TEMPLATE).statusCode(400)
                .body(containsString("AlreadyExistsException"));
    }

    @Test
    void racingCreateStacksOverARootInReviewTakeItOnce() throws InterruptedException {
        String rootId = createChangeSet(name, "an-old-create", QUEUE_TEMPLATE);
        int attempts = 16;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger conflicted = new AtomicInteger();
        Set<String> taken = ConcurrentHashMap.newKeySet();
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    String body = cfn("CreateStack", "StackName", name, "TemplateBody", QUEUE_TEMPLATE)
                            .extract().asString();
                    if (body.contains("AlreadyExistsException")) {
                        conflicted.incrementAndGet();
                    } else if (body.contains("<StackId>")) {
                        succeeded.incrementAndGet();
                        taken.add(XmlPath.from(body).getString("CreateStackResponse.CreateStackResult.StackId"));
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers failed to line up in time");
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "requests did not finish in time");
        }

        assertEquals(1, succeeded.get(), "exactly one CreateStack should take the root");
        assertEquals(attempts - 1, conflicted.get(), "every other request should see AlreadyExistsException");
        assertEquals(Set.of(rootId), taken);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        assertEquals(List.of("Queue"), resources(name));
    }

    /** A CREATE change set on a name no stack holds yet: the ID of the root it leaves in review. */
    private static String createChangeSet(String stack, String changeSet, String template) {
        return cfn("CreateChangeSet", "StackName", stack, "ChangeSetName", changeSet, "ChangeSetType", "CREATE",
                "TemplateBody", template).statusCode(200)
                .extract().xmlPath().getString("CreateChangeSetResponse.CreateChangeSetResult.StackId");
    }

    private static String status(String stack) {
        return describeStack(stack).getString(stackPath("StackStatus"));
    }

    private static XmlPath describeStack(String stack) {
        return cfn("DescribeStacks", "StackName", stack).statusCode(200).extract().xmlPath();
    }

    private static String stackPath(String member) {
        return "DescribeStacksResponse.DescribeStacksResult.Stacks.member." + member;
    }

    private static List<String> resources(String stack) {
        return cfn("DescribeStackResources", "StackName", stack).statusCode(200).extract().xmlPath()
                .getList("DescribeStackResourcesResponse.DescribeStackResourcesResult.StackResources.member"
                        + ".LogicalResourceId");
    }

    private static List<String> changeSetNames(String stack) {
        return cfn("ListChangeSets", "StackName", stack).statusCode(200).extract().xmlPath()
                .getList("ListChangeSetsResponse.ListChangeSetsResult.Summaries.member.ChangeSetName");
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
