package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::IAM::RolePolicy} puts one inline policy on one role: {@code Ref} is the registry's
 * {@code <PolicyName>|<RoleName>}, a changed document is put in place, a changed policy name replaces the
 * policy and removes the old one from the role, and deleting the stack removes it. IAM is the Query API, so
 * no containers are involved.
 */
@QuarkusTest
class CloudFormationIamRolePolicyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/iam/aws4_request";

    private static String template(String roleName, String policyName, String action) {
        return """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%1$s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
                                         "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "ReadPolicy": {
                      "Type": "AWS::IAM::RolePolicy",
                      "Properties": {
                        "RoleName": {"Ref": "Role"},
                        "PolicyName": "%2$s",
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Action": "%3$s", "Resource": "*"}]
                        }
                      }
                    }
                  },
                  "Outputs": {"PolicyRef": {"Value": {"Ref": "ReadPolicy"}}}
                }
                """.formatted(roleName, policyName, action);
    }

    @Test
    void putsUpdatesReplacesAndRemovesAnInlinePolicyOnItsRole() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-iam-role-policy-" + suffix;
        String role = "rp-role-" + suffix;
        String policy = "rp-read-" + suffix;

        cloudFormation("CreateStack", stack, template(role, policy, "s3:GetObject"));
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE");
        assertEquals(policy + "|" + role, outputs(stack).get("PolicyRef"));
        getRolePolicy(role, policy).statusCode(200).body(containsString("s3:GetObject"));

        cloudFormation("UpdateStack", stack, template(role, policy, "s3:PutObject"));
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");
        assertEquals(policy + "|" + role, outputs(stack).get("PolicyRef"));
        getRolePolicy(role, policy).statusCode(200).body(containsString("s3:PutObject"));

        String renamed = "rp-write-" + suffix;
        cloudFormation("UpdateStack", stack, template(role, renamed, "s3:PutObject"));
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");
        assertEquals(renamed + "|" + role, outputs(stack).get("PolicyRef"));
        getRolePolicy(role, renamed).statusCode(200).body(containsString("s3:PutObject"));
        getRolePolicy(role, policy).statusCode(404);

        cloudFormation("DeleteStack", stack, null);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
        getRolePolicy(role, renamed).statusCode(404);
    }

    private static void cloudFormation(String action, String stack, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String stack) {
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse getRolePolicy(String role, String policy) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRolePolicy")
                .formParam("RoleName", role)
                .formParam("PolicyName", policy)
                .when().post("/").then();
    }
}
