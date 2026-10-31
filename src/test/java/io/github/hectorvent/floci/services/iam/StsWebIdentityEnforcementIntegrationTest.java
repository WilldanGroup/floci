package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SelfSignedOidcIssuer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code AssumeRoleWithWebIdentity} admits and refuses under IAM enforcement from an issuer
 * Floci does not host: one the role's account declares as an OIDC provider is read and verified,
 * and every other token gets the answer real STS gave for it, read on 2026-10-31 from
 * {@code sts.us-east-1.amazonaws.com}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsWebIdentityEnforcementIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "222233334444";
    private static final String CLIENT_ID = "my-client";

    private static final String NOT_A_JWT =
            "The ID Token provided is not a valid JWT. (You may see this error if you sent an Access Token)";
    private static final String NOT_HTTPS = "Issuer must start with https://";
    private static final String UNSIGNED = "We don't accept unsigned JWTs";
    private static final String NOT_VALIDATED = "The web identity token provided could not be validated. "
            + "See the AssumeRoleWithWebIdentity documentation for requirements.";
    // Two spaces after the comma, as AWS sent it.
    private static final String NOT_RETRIEVED = "Couldn't retrieve verification key from your identity provider,  "
            + "please reference AssumeRoleWithWebIdentity documentation for requirements";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private SelfSignedOidcIssuer issuer;
    private String roleArn;

    @BeforeEach
    void startIssuer() throws Exception {
        issuer = SelfSignedOidcIssuer.start();
        roleArn = createRole(ACCOUNT, "web-identity-" + issuer.hostAndPort().replace(':', '-'),
                trustPolicy(ACCOUNT, issuer.hostAndPort(), CLIENT_ID));
    }

    @AfterEach
    void stopIssuer() {
        issuer.close();
    }

    // ── A declared issuer ────────────────────────────────────────────────────

    @Test
    void aTokenFromADeclaredIssuerAnswersCredentialsThatActAsTheRole() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        ValidatableResponse assumed = assumeRole(roleArn, issuer.token(CLIENT_ID)).then()
                .statusCode(200)
                .body(containsString("<SubjectFromWebIdentityToken>subject-1</SubjectFromWebIdentityToken>"))
                .body(containsString("<Provider>" + issuer.url() + "</Provider>"))
                .body(containsString("<Audience>" + CLIENT_ID + "</Audience>"));

        String prefix = "AssumeRoleWithWebIdentityResponse.AssumeRoleWithWebIdentityResult.";
        String accessKeyId = assumed.extract().path(prefix + "Credentials.AccessKeyId");
        String sessionToken = assumed.extract().path(prefix + "Credentials.SessionToken");
        String assumedRoleArn = assumed.extract().path(prefix + "AssumedRoleUser.Arn");
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20261031/us-east-1/sts/aws4_request")
                .header("X-Amz-Security-Token", sessionToken)
        .when().post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(assumedRoleArn));
    }

    @Test
    void theAudienceAnsweredIsTheClientIdTheProviderLists() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        String role = createRole(ACCOUNT, "two-audiences-" + issuer.hostAndPort().replace(':', '-'),
                federatedTrust(ACCOUNT, issuer.hostAndPort(), "\"StringLike\":{\"" + issuer.hostAndPort() + ":aud\":\"*\"}"));
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.put("aud", List.of("unlisted", CLIENT_ID));

        assumeRole(role, issuer.signed(claims)).then()
                .statusCode(200)
                .body(containsString("<Audience>" + CLIENT_ID + "</Audience>"));
    }

    @Test
    void aRoleTrustingAnotherProviderIsRefused() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        String role = createRole(ACCOUNT, "other-provider-" + issuer.hostAndPort().replace(':', '-'),
                trustPolicy(ACCOUNT, "other.example", CLIENT_ID));

        assertAccessDenied(role, issuer.token(CLIENT_ID));
    }

    @Test
    void aRoleTrustingAnotherAudienceIsRefused() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        String role = createRole(ACCOUNT, "other-audience-" + issuer.hostAndPort().replace(':', '-'),
                trustPolicy(ACCOUNT, issuer.hostAndPort(), "another-client"));

        assertAccessDenied(role, issuer.token(CLIENT_ID));
    }

    @Test
    void aRoleThatDoesNotExistIsRefusedBeforeAnyRequest() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertAccessDenied("arn:aws:iam::" + ACCOUNT + ":role/no-such-role", issuer.token(CLIENT_ID));
        assertEquals(0, issuer.requests());
    }

    @Test
    void aKeySetOfSeveralKeysChecksTheKeyTheTokenNames() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.addKey("key-2");

        assumeRole(roleArn, issuer.token(CLIENT_ID)).then().statusCode(200);
    }

    @Test
    void aTokenWithNoKidIsVerifiedUnderTheOnlyKey() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assumeRole(roleArn, issuer.signed(issuer.claims(CLIENT_ID), null)).then().statusCode(200);
    }

    @Test
    void aTokenWithNoKidIsRefusedAmongSeveralKeys() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.addKey("key-2");

        assertRefused(issuer.signed(issuer.claims(CLIENT_ID), null), NOT_RETRIEVED);
    }

    // ── Reading the issuer ───────────────────────────────────────────────────

    @Test
    void rotatedKeysAreReadOnTheNextCall() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        String before = issuer.token(CLIENT_ID);
        assumeRole(roleArn, before).then().statusCode(200);

        issuer.rotateTo("key-2");

        assumeRole(roleArn, issuer.token(CLIENT_ID)).then().statusCode(200);
        assertRefused(before, NOT_RETRIEVED);
    }

    @Test
    void aDiscoveryDocumentThatIsNotThereAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.hideDiscovery();

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    @Test
    void anIssuerThatCannotBeReachedAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        String token = issuer.token(CLIENT_ID);
        issuer.close();

        assertRefused(token, NOT_RETRIEVED);
    }

    @Test
    void aDiscoveryDocumentNamingAnotherIssuerAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.declareIssuerAs("https://other.example");

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.redirectDiscoveryTo(issuer.url() + "/elsewhere");

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    @Test
    void aKeySetThatIsNotHttpsIsNotRead() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        issuer.pointKeysAt(issuer.url().replace("https://", "http://") + "/keys");

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    // ── The issuer's certificate ─────────────────────────────────────────────

    @Test
    void theDeclaredThumbprintMatchesInEitherCase() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint().toUpperCase(Locale.ROOT));

        assumeRole(roleArn, issuer.token(CLIENT_ID)).then().statusCode(200);
    }

    @Test
    void anotherThumbprintAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer, "990f4193972f2becf12ddeda5237f9c952f20d9e");

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    @Test
    void noThumbprintAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer);

        assertRefused(issuer.token(CLIENT_ID), NOT_RETRIEVED);
    }

    @Test
    void aCertificateNamingAnotherHostAnswersNotRetrievedWhateverItsThumbprint() throws Exception {
        try (SelfSignedOidcIssuer elsewhere = SelfSignedOidcIssuer.start("elsewhere.example")) {
            declare(ACCOUNT, elsewhere, elsewhere.thumbprint());
            String role = createRole(ACCOUNT, "other-host-" + elsewhere.hostAndPort().replace(':', '-'),
                    trustPolicy(ACCOUNT, elsewhere.hostAndPort(), CLIENT_ID));

            assertRefused(role, elsewhere.token(CLIENT_ID), "InvalidIdentityToken", NOT_RETRIEVED);
        }
    }

    // ── Every other token, in the order real STS checks ──────────────────────

    @Test
    void anOpaqueTokenIsNotAJwt() {
        assertRefused("local-stage-stand-in", NOT_A_JWT);
    }

    @Test
    void aJwtWithNoIssuerIsNotAJwt() {
        String header = Base64Url.encode("{\"alg\":\"none\"}");
        String payload = Base64Url.encode("{\"aud\":\"sts.amazonaws.com\"}");

        assertRefused(header + "." + payload + ".", NOT_A_JWT);
    }

    @Test
    void aTokenNamingADeclaredHostByHttpIsRefusedWithNoRequest() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.put("iss", issuer.url().replace("https://", "http://"));

        assertRefused(issuer.signed(claims), NOT_HTTPS);
        assertEquals(0, issuer.requests());
    }

    @Test
    void anHttpProviderCannotBeDeclared() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", iamAuth(ACCOUNT, "iam"))
                .formParam("Action", "CreateOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("Url", "http://issuer.example:8080")
                .formParam("ClientIDList.member.1", CLIENT_ID)
        .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void anUnsignedTokenNamingAnUndeclaredIssuerIsNotValidated() throws Exception {
        assertRefused(SelfSignedOidcIssuer.unsigned(issuer.claims(CLIENT_ID)), NOT_VALIDATED);
    }

    @Test
    void aSignedTokenFromAnUndeclaredIssuerIsNotValidatedWithNoRequest() throws Exception {
        assertRefused(issuer.token(CLIENT_ID), NOT_VALIDATED);
        assertEquals(0, issuer.requests());
    }

    @Test
    void aProviderInAnotherAccountIsNotRead() throws Exception {
        declare(OTHER_ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(issuer.token(CLIENT_ID), NOT_VALIDATED);
        assertEquals(0, issuer.requests());
    }

    @Test
    void anExpiredTokenWhoseKeyIsUnknownToo() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        long expiry = System.currentTimeMillis() / 1000 - 86_400;
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.put("exp", expiry);

        assumeRole(roleArn, issuer.signed(claims, "no-such-key")).then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ExpiredTokenException"))
                .body("ErrorResponse.Error.Message", matchesPattern(
                        "Token expired: current date/time \\d+ must be before the expiration date/time " + expiry));
        assertEquals(0, issuer.requests());
    }

    @Test
    void anUnsignedTokenNamingADeclaredIssuerIsRefusedWithNoRequest() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(SelfSignedOidcIssuer.unsigned(issuer.claims(CLIENT_ID)), UNSIGNED);
        assertEquals(0, issuer.requests());
    }

    @Test
    void anUnlistedAudienceWhoseKeyIsUnknownTooIsRefusedWithNoRequest() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(issuer.signed(issuer.claims("unlisted"), "no-such-key"), NOT_VALIDATED);
        assertEquals(0, issuer.requests());
    }

    @Test
    void aKidTheKeySetDoesNotHoldAnswersNotRetrieved() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(issuer.signed(issuer.claims(CLIENT_ID), "no-such-key"), NOT_RETRIEVED);
    }

    @Test
    void aSignatureByAnotherKeyIsNotValidated() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(SelfSignedOidcIssuer.signed(issuer.claims(CLIENT_ID), "key-1",
                SelfSignedOidcIssuer.newKey().getPrivate()), NOT_VALIDATED);
    }

    @Test
    void anotherAlgorithmIsNotValidated() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());

        assertRefused(SelfSignedOidcIssuer.underAlgorithm("HS256", issuer.claims(CLIENT_ID)), NOT_VALIDATED);
    }

    @Test
    void aTokenBeforeItsNotBeforeIsNotValidated() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.put("nbf", System.currentTimeMillis() / 1000 + 3_600);

        assertRefused(issuer.signed(claims), NOT_VALIDATED);
    }

    @Test
    void aTokenWithNoSubjectIsNotValidated() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.remove("sub");

        assertRefused(issuer.signed(claims), NOT_VALIDATED);
    }

    @Test
    void aTokenWithNoExpiryIsNotValidated() throws Exception {
        declare(ACCOUNT, issuer, issuer.thumbprint());
        Map<String, Object> claims = issuer.claims(CLIENT_ID);
        claims.remove("exp");

        assertRefused(issuer.signed(claims), NOT_VALIDATED);
    }

    // ── A hosted issuer that is also declared ────────────────────────────────

    @Test
    void aHostedIssuerAlsoDeclaredIsVerifiedByItsOwnPath() {
        String cluster = "declared-" + issuer.hostAndPort().replace(':', '-');
        String clusterIssuer = given()
                .contentType("application/json")
                .header("Authorization", iamAuth(ACCOUNT, "eks"))
                .body("{\"name\":\"" + cluster + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/eks\"}")
        .when().post("/clusters").then().statusCode(200)
                .extract().path("cluster.identity.oidc.issuer");
        String hostAndPath = clusterIssuer.replaceFirst("^https://", "");
        String providerArn = createProvider(ACCOUNT, clusterIssuer, List.of(CLIENT_ID), List.of());
        cleanup.register(() -> deleteProvider(ACCOUNT, providerArn));
        String role = createRole(ACCOUNT, "hosted-" + issuer.hostAndPort().replace(':', '-'),
                federatedTrust(ACCOUNT, hostAndPath, ""));

        String stsToken = clusterToken(cluster, "sts.amazonaws.com");
        assumeRole(role, stsToken).then()
                .statusCode(200)
                .body(containsString("<Audience>sts.amazonaws.com</Audience>"));

        // Listed by the provider, and still refused: the cluster's path requires its own audience.
        assertRefused(role, clusterToken(cluster, CLIENT_ID), "InvalidIdentityToken",
                "The web identity token audience does not include sts.amazonaws.com");
        assertEquals(0, issuer.requests());
    }

    private String clusterToken(String cluster, String audience) {
        return given()
                .contentType("application/json")
                .header("Authorization", iamAuth(ACCOUNT, "eks"))
                .body("{\"namespace\":\"apps\",\"serviceAccount\":\"app\",\"audience\":\"" + audience + "\"}")
        .when().post("/_floci/eks/clusters/" + cluster + "/oidc-token").then().statusCode(200)
                .extract().path("token");
    }

    // ── A template declaring the provider ────────────────────────────────────

    @Test
    void aStacksProviderAndRoleAdmitATokenUntilTheStackIsDeleted() throws Exception {
        String stack = "oidc-" + issuer.hostAndPort().replace(':', '-');
        String role = "stack-" + issuer.hostAndPort().replace(':', '-');
        String template = """
                {"Resources":{
                  "Provider":{"Type":"AWS::IAM::OIDCProvider","Properties":{
                    "Url":"%s","ClientIdList":["%s"],"ThumbprintList":["%s"]}},
                  "Role":{"Type":"AWS::IAM::Role","Properties":{"RoleName":"%s",
                    "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                      "Principal":{"Federated":{"Ref":"Provider"}},
                      "Action":"sts:AssumeRoleWithWebIdentity",
                      "Condition":{"StringEquals":{"%s:aud":"%s"}}}]}}}}}
                """.formatted(issuer.url(), CLIENT_ID, issuer.thumbprint(), role, issuer.hostAndPort(), CLIENT_ID);
        String cfnAuth = iamAuth(ACCOUNT, "cloudformation");
        given().contentType("application/x-www-form-urlencoded").header("Authorization", cfnAuth)
                .formParam("Action", "CreateStack").formParam("StackName", stack).formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, cfnAuth).status());

        String stackRoleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + role;
        assumeRole(stackRoleArn, issuer.token(CLIENT_ID)).then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", cfnAuth)
                .formParam("Action", "DeleteStack").formParam("StackName", stack)
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack, cfnAuth);

        assertRefused(issuer.token(CLIENT_ID), NOT_VALIDATED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertRefused(String token, String message) {
        assertRefused(roleArn, token, "InvalidIdentityToken", message);
    }

    private static void assertRefused(String role, String token, String code, String message) {
        assumeRole(role, token).then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo(code))
                .body("ErrorResponse.Error.Message", equalTo(message));
    }

    private static void assertAccessDenied(String role, String token) {
        assumeRole(role, token).then()
                .statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    private void declare(String account, SelfSignedOidcIssuer declared, String... thumbprints) {
        String arn = createProvider(account, declared.url(), List.of(CLIENT_ID), List.of(thumbprints));
        cleanup.register(() -> deleteProvider(account, arn));
    }

    private static String createProvider(String account, String url, List<String> clientIds, List<String> thumbprints) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", iamAuth(account, "iam"))
                .formParam("Action", "CreateOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("Url", url);
        for (int i = 0; i < clientIds.size(); i++) {
            request.formParam("ClientIDList.member." + (i + 1), clientIds.get(i));
        }
        for (int i = 0; i < thumbprints.size(); i++) {
            request.formParam("ThumbprintList.member." + (i + 1), thumbprints.get(i));
        }
        return request.when().post("/").then().statusCode(200)
                .extract().path("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
    }

    private static void deleteProvider(String account, String arn) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", iamAuth(account, "iam"))
                .formParam("Action", "DeleteOpenIDConnectProvider")
                .formParam("Version", "2010-05-08")
                .formParam("OpenIDConnectProviderArn", arn)
        .when().post("/").then().statusCode(200);
    }

    private String createRole(String account, String name, String trustPolicy) {
        String arn = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", iamAuth(account, "iam"))
                .formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trustPolicy)
        .when().post("/").then().statusCode(200)
                .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", iamAuth(account, "iam"))
                .formParam("Action", "DeleteRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", name)
                .when().post("/"));
        return arn;
    }

    private static String trustPolicy(String account, String hostAndPath, String audience) {
        return federatedTrust(account, hostAndPath,
                "\"StringEquals\":{\"" + hostAndPath + ":aud\":\"" + audience + "\"}");
    }

    private static String federatedTrust(String account, String hostAndPath, String condition) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%s:oidc-provider/%s"},\
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{%s}}]}"""
                .formatted(account, hostAndPath, condition);
    }

    private static String iamAuth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261031/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static Response assumeRole(String role, String token) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", role)
            .formParam("RoleSessionName", "enforcement-test-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/");
    }
}
