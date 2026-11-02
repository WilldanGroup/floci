package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With {@code iam.enforcement-enabled=true}, a CloudFormation request is authorized against the stack it names, by
 * name or ARN, {@code stack/<name>/<id>}, the resource the Service Authorization Reference gives every stack action,
 * change sets' included, with {@code aws:ResourceTag/<key>} from the stack's tags (Actions, resources, and condition
 * keys for AWS CloudFormation). A role granted the account's stacks only where a tag is set reads a tagged stack and
 * is refused an untagged one, as a role scoped to another stack's name is refused this one. And
 * {@code cloudformation:ChangeSetName} carries the change set's name as an execute or a delete sends it, a name or
 * an ARN, and is absent from a describe, as AWS was measured to carry it.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFormationStackEnforcementIntegrationTest {

    private static final String ACCOUNT = "121212121212";
    private static final String STACKS = "arn:aws:cloudformation:*:" + ACCOUNT + ":stack/*";
    private static final String TEMPLATE = "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    private String tagged;
    private String untagged;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTwoStacks() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tagged = "tagged-" + suffix;
        untagged = "untagged-" + suffix;
        createStack(tagged, true);
        createStack(untagged, false);
    }

    @Test
    void aRoleGrantedTaggedStacksDescribesATaggedStackByNameOrArnAndIsRefusedAnUntaggedOne() {
        Session session = assumeRoleWith("""
                {"Effect":"Allow","Action":"cloudformation:DescribeStacks","Resource":"%s",
                 "Condition":{"StringLike":{"aws:ResourceTag/swb:tenant":"*"}}}""".formatted(STACKS));

        cloudFormation(session, "DescribeStacks", "StackName", tagged).statusCode(200)
                .body(containsString("<StackName>" + tagged + "</StackName>"));
        cloudFormation(session, "DescribeStacks", "StackName", stackId(tagged)).statusCode(200)
                .body(containsString("<StackName>" + tagged + "</StackName>"));
        cloudFormation(session, "DescribeStacks", "StackName", untagged).statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void aRoleScopedToAnotherStacksNameIsRefusedThisOne() {
        Session session = assumeRoleWith("""
                {"Effect":"Allow","Action":"cloudformation:DescribeStacks",
                 "Resource":"arn:aws:cloudformation:*:%s:stack/%s/*"}""".formatted(ACCOUNT, untagged));

        cloudFormation(session, "DescribeStacks", "StackName", untagged).statusCode(200);
        cloudFormation(session, "DescribeStacks", "StackName", tagged).statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void theChangeSetNameIsCarriedOnADeleteByNameOrArnAndNotOnADescribe() {
        String allowed = changeSet(tagged, "swb-" + UUID.randomUUID().toString().substring(0, 8));
        String byArn = changeSet(tagged, "swb-" + UUID.randomUUID().toString().substring(0, 8));
        String refused = changeSet(tagged, "other-" + UUID.randomUUID().toString().substring(0, 8));
        Session session = assumeRoleWith("""
                {"Effect":"Allow","Action":["cloudformation:DeleteChangeSet","cloudformation:DescribeChangeSet"],
                 "Resource":"%s",
                 "Condition":{"StringLike":{"cloudformation:ChangeSetName":["swb-*","arn:*:changeSet/swb-*/*"]}}}"""
                .formatted(STACKS));

        cloudFormation(session, "DescribeChangeSet", "StackName", tagged, "ChangeSetName", name(allowed))
                .statusCode(403).body(containsString("AccessDenied"));
        cloudFormation(session, "DeleteChangeSet", "StackName", tagged, "ChangeSetName", name(allowed))
                .statusCode(200);
        cloudFormation(session, "DeleteChangeSet", "StackName", tagged, "ChangeSetName", byArn).statusCode(200);
        cloudFormation(session, "DeleteChangeSet", "StackName", tagged, "ChangeSetName", name(refused))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    private void createStack(String name, boolean tag) {
        List<String> params = new ArrayList<>(List.of("StackName", name, "TemplateBody", TEMPLATE));
        if (tag) {
            params.addAll(List.of("Tags.member.1.Key", "swb:tenant", "Tags.member.1.Value", "t1"));
        }
        accountCall("CreateStack", params.toArray(String[]::new)).statusCode(200);
        cleanup.register(() -> {
            accountCall("DeleteStack", "StackName", name).statusCode(200);
            CfnStackWaits.awaitStackDeleted(name, auth(ACCOUNT, "cloudformation"));
        });
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name, auth(ACCOUNT, "cloudformation")).status());
    }

    /** A change set of the stack's own template, made as the account: its ARN. */
    private String changeSet(String stack, String name) {
        String arn = accountCall("CreateChangeSet", "StackName", stack, "ChangeSetName", name,
                "UsePreviousTemplate", "true").statusCode(200)
                .extract().xmlPath().getString("CreateChangeSetResponse.CreateChangeSetResult.Id");
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = accountCall("DescribeChangeSet", "StackName", stack, "ChangeSetName", name)
                    .statusCode(200).extract().xmlPath().getString("DescribeChangeSetResponse.DescribeChangeSetResult.Status");
            if (!status.endsWith("_IN_PROGRESS") && !"CREATE_PENDING".equals(status)) {
                return arn;
            }
            sleep();
        }
        return arn;
    }

    private static String name(String changeSetArn) {
        String rest = changeSetArn.substring(changeSetArn.indexOf(":changeSet/") + ":changeSet/".length());
        return rest.substring(0, rest.indexOf('/'));
    }

    private String stackId(String stack) {
        return accountCall("DescribeStacks", "StackName", stack).statusCode(200)
                .extract().xmlPath().getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackId");
    }

    /** A session of a new role that its own account may assume, allowed by the one {@code statement}. */
    private Session assumeRoleWith(String statement) {
        int port = endpoint.getPort();
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/",
                "cfn-stack-" + UUID.randomUUID().toString().substring(0, 8),
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}");
        return EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
    }

    private ValidatableResponse cloudFormation(Session session, String action, String... params) {
        RequestSpecification request = given().port(endpoint.getPort()).header("Authorization", auth(session.accessKeyId(), "cloudformation"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/").then();
    }

    /** A call as the account itself, which the cleanup also makes after RestAssured's port is reset. */
    private ValidatableResponse accountCall(String action, String... params) {
        RequestSpecification request = given().port(endpoint.getPort()).header("Authorization", auth(ACCOUNT, "cloudformation"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/").then();
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
