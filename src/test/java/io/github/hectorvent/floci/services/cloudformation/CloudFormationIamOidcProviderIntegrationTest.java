package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.CloudFormationSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AWS::IAM::OIDCProvider} through a stack, with stubbing off so a resource nothing provisions
 * fails rather than passing: {@code Ref} and {@code Arn}, an update in place, a replaced
 * {@code Url}, the stack's deletion, and an {@code http://} {@code Url} failing the resource.
 */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
class CloudFormationIamOidcProviderIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261031/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261031/us-east-1/iam/aws4_request";

    @Test
    void aStackStandsAProviderAndDeletingItRemovesIt() {
        String suffix = suffix();
        String url = "https://issuer-" + suffix + ".example/path";
        String arn = providerArn(url);
        String stack = "oidc-create-" + suffix;

        submit("CreateStack", stack, template(url, "[\"client-a\"]", "[\"aa11\"]", "[{\"Key\":\"team\",\"Value\":\"blue\"}]"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        assertOutput(stack, "ProviderRef", arn);
        assertOutput(stack, "ProviderArn", arn);
        ValidatableResponse provider = getProvider(arn).statusCode(200);
        assertEquals("issuer-" + suffix + ".example/path",
                provider.extract().path("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.Url"));
        provider.body(containsString("<member>client-a</member>"))
                .body(containsString("<member>aa11</member>"))
                .body(containsString("<Key>team</Key>"));

        delete(stack);
        getProvider(arn).statusCode(404);
    }

    @Test
    void anUpdateChangesClientIdsThumbprintsAndTagsInPlace() {
        String suffix = suffix();
        String url = "https://issuer-" + suffix + ".example";
        String arn = providerArn(url);
        String stack = "oidc-update-" + suffix;
        submit("CreateStack", stack, template(url, "[\"client-a\",\"client-b\"]", "[\"aa11\"]",
                "[{\"Key\":\"team\",\"Value\":\"blue\"},{\"Key\":\"old\",\"Value\":\"x\"}]"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String createDate = getProvider(arn).extract().path(
                "GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.CreateDate");

        submit("UpdateStack", stack, template(url, "[\"client-b\",\"client-c\"]", "[\"bb22\"]",
                "[{\"Key\":\"team\",\"Value\":\"green\"}]"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        assertOutput(stack, "ProviderArn", arn);
        ValidatableResponse provider = getProvider(arn).statusCode(200);
        assertEquals(createDate, provider.extract().path(
                "GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.CreateDate"));
        provider.body(not(containsString("<member>client-a</member>")))
                .body(containsString("<member>client-b</member>"))
                .body(containsString("<member>client-c</member>"))
                .body(not(containsString("aa11")))
                .body(containsString("<member>bb22</member>"))
                .body(containsString("<Value>green</Value>"))
                .body(not(containsString("<Key>old</Key>")));

        delete(stack);
    }

    @Test
    void aChangedUrlReplacesTheProvider() {
        String suffix = suffix();
        String first = "https://first-" + suffix + ".example";
        String second = "https://second-" + suffix + ".example";
        String stack = "oidc-replace-" + suffix;
        submit("CreateStack", stack, template(first, "[\"client-a\"]", "[\"aa11\"]", "[]"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        submit("UpdateStack", stack, template(second, "[\"client-a\"]", "[\"aa11\"]", "[]"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());

        assertOutput(stack, "ProviderArn", providerArn(second));
        getProvider(providerArn(second)).statusCode(200);
        getProvider(providerArn(first)).statusCode(404);

        delete(stack);
        getProvider(providerArn(second)).statusCode(404);
    }

    @Test
    void anHttpUrlFailsTheResource() {
        String stack = "oidc-http-" + suffix();

        submit("CreateStack", stack, template("http://issuer.example:8080", "[\"client-a\"]", "[]", "[]"));

        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertThat(CfnStackWaits.failedResourceReason(stack, CFN_AUTH),
                containsString("The OpenID Connect provider URL must begin with https://."));
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "ListOpenIDConnectProviders")
                .formParam("Version", "2010-05-08")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("issuer.example:8080")));
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String providerArn(String url) {
        return "arn:aws:iam::" + ACCOUNT + ":oidc-provider/" + url.substring("https://".length());
    }

    private static String template(String url, String clientIds, String thumbprints, String tags) {
        return """
                {"Resources":{"Provider":{"Type":"AWS::IAM::OIDCProvider","Properties":{
                  "Url":"%s","ClientIdList":%s,"ThumbprintList":%s,"Tags":%s}}},
                 "Outputs":{"ProviderRef":{"Value":{"Ref":"Provider"}},
                            "ProviderArn":{"Value":{"Fn::GetAtt":["Provider","Arn"]}}}}
                """.formatted(url, clientIds, thumbprints, tags);
    }

    private static void submit(String action, String stack, String template) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack)
                .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private static void delete(String stack) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stack)
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
    }

    private static void assertOutput(String stack, String key, String value) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
                .body(containsString("<OutputKey>" + key + "</OutputKey>"))
                .body(containsString("<OutputValue>" + value + "</OutputValue>"));
    }

    private static ValidatableResponse getProvider(String arn) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("OpenIDConnectProviderArn", arn)
        .when().post("/").then();
    }
}
