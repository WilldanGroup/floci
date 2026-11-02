package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * EvaluateCode runs one handler of {@code APPSYNC_JS} code against the context it is handed, in the runtime the
 * resolvers run in, and answers its result as JSON text (AWS AppSync API Reference, EvaluateCode).
 *
 * <p>Needs Docker for the Node sidecar, and skips without it.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncEvaluateCodeDockerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    private static final String CODE = """
            import { util } from '@aws-appsync/utils';
            export function request(ctx) {
              ctx.stash.seen = ctx.args.text;
              return util.str.normalize(ctx.arguments.text, ctx.arguments.form);
            }
            export function response(ctx) {
              if (ctx.result === 'refuse') {
                util.error('refused by the handler', 'Refused');
              }
              return ctx.result;
            }
            """;

    @BeforeAll
    static void configure() {
        SidecarContainersProfile.requireDocker();
    }

    @Test
    void theRequestHandlerIsRunAgainstTheContextAndItsResultAnsweredAsJsonText() {
        JsonPath answer = evaluate(CODE, "{\"arguments\":{\"text\":\"\\ufb01\",\"form\":\"nfkd\"}}", "request")
                .statusCode(200).extract().jsonPath();

        assertNull(answer.get("error"));
        assertEquals("\"fi\"", answer.getString("evaluationResult"));
        assertEquals("{\"seen\":\"\ufb01\"}", answer.getString("stash"));
        assertEquals("[]", answer.getString("outErrors"));
    }

    @Test
    void theResponseHandlerIsRunWhenNamedAndAnErrorItRaisesIsAnswered() {
        JsonPath kept = evaluate(CODE, "{\"result\":{\"id\":\"a\"}}", "response")
                .statusCode(200).extract().jsonPath();
        assertEquals("{\"id\":\"a\"}", kept.getString("evaluationResult"));

        JsonPath refused = evaluate(CODE, "{\"result\":\"refuse\"}", "response")
                .statusCode(200).extract().jsonPath();
        assertEquals("refused by the handler", refused.getString("error.message"));
        assertNull(refused.get("evaluationResult"));
    }

    @Test
    void aRuntimeOtherThanAppSyncJsIsRefused() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"code\":\"x\",\"context\":\"{}\",\"runtime\":{\"name\":\"VTL\",\"runtimeVersion\":\"1.0.0\"}}")
        .when()
                .post("/v1/dataplane-evaluatecode")
        .then()
                .statusCode(400)
                .body(containsString("BadRequestException"));
    }

    private static ValidatableResponse evaluate(String code, String context, String function) {
        return given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(Map.of(
                        "code", code,
                        "context", context,
                        "function", function,
                        "runtime", Map.of("name", "APPSYNC_JS", "runtimeVersion", "1.0.0")))
        .when()
                .post("/v1/dataplane-evaluatecode")
        .then();
    }
}
