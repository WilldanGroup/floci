package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SelfSignedOidcIssuer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With IAM enforcement off, a token from an issuer Floci does not host is accepted unverified,
 * whether or not a provider declares it, and no request reaches an issuer.
 */
@QuarkusTest
class StsWebIdentityEnforcementOffIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261031/us-east-1/iam/aws4_request";

    private SelfSignedOidcIssuer issuer;
    private String roleArn;
    private String providerArn;

    @BeforeEach
    void declareIssuer() throws Exception {
        issuer = SelfSignedOidcIssuer.start();
        providerArn = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "CreateOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("Url", issuer.url())
                .formParam("ClientIDList.member.1", "my-client")
                .formParam("ThumbprintList.member.1", issuer.thumbprint())
        .when().post("/").then().statusCode(200)
                .extract().path("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
        roleArn = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", roleName())
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
        .when().post("/").then().statusCode(200)
                .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    @AfterEach
    void removeIssuer() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", roleName())
        .when().post("/");
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("OpenIDConnectProviderArn", providerArn)
        .when().post("/");
        issuer.close();
    }

    private String roleName() {
        return "enforcement-off-" + issuer.hostAndPort().replace(':', '-');
    }

    @Test
    void anOpaqueTokenAnswersCredentials() {
        assertAdmittedWithNoRequest("local-stage-stand-in");
    }

    @Test
    void aTokenNamingAnHttpIssuerAnswersCredentials() throws Exception {
        Map<String, Object> claims = issuer.claims("my-client");
        claims.put("iss", issuer.url().replace("https://", "http://"));

        assertAdmittedWithNoRequest(issuer.signed(claims));
    }

    @Test
    void aTokenNamingADeclaredIssuerWithABadSignatureAnswersCredentials() throws Exception {
        assertAdmittedWithNoRequest(SelfSignedOidcIssuer.signed(issuer.claims("my-client"), "key-1",
                SelfSignedOidcIssuer.newKey().getPrivate()));
    }

    private void assertAdmittedWithNoRequest(String token) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRoleWithWebIdentity")
                .formParam("Version", "2011-06-15")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "enforcement-off-session")
                .formParam("WebIdentityToken", token)
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<AccessKeyId>ASIA"));
        assertEquals(0, issuer.requests());
    }
}
