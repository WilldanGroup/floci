package io.github.hectorvent.floci.services.lambda;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambda writes a START, an END and a REPORT line to the function's log stream for each invocation, whatever the
 * function itself prints (Lambda Developer Guide, Sending Lambda function logs to CloudWatch Logs), so a function
 * that prints nothing still fills the log group its LoggingConfig names.
 */
@QuarkusTest
class LambdaPlatformLogDockerIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    @Inject
    DockerClient dockerClient;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Lambda log tests");
    }

    @Test
    void aSilentFunctionsInvocationIsLoggedStartEndAndReport() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "platform-log-" + suffix;
        String logGroup = "/floci/platform-log-" + suffix;
        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "nodejs20.x",
                      "Role": "%s",
                      "Handler": "index.handler",
                      "Timeout": 10,
                      "MemorySize": 256,
                      "LoggingConfig": {"LogGroup": "%s"},
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(functionName, ROLE, logGroup, silentZipBase64()))
        .when()
                .post(BASE_PATH + "/functions")
        .then()
                .statusCode(201);
        try {
            String requestId = given()
                    .body("{}")
            .when()
                    .post(BASE_PATH + "/functions/" + functionName + "/invocations")
            .then()
                    .statusCode(200)
                    .extract().header("x-amzn-RequestId");

            List<String> messages = awaitMessages(logGroup, 3);
            assertEquals("START RequestId: " + requestId + " Version: $LATEST", messages.get(0).trim());
            assertEquals("END RequestId: " + requestId, messages.get(1).trim());
            assertTrue(messages.get(2).startsWith("REPORT RequestId: " + requestId + "\tDuration: "), messages.get(2));
            assertTrue(messages.get(2).contains("\tMemory Size: 256 MB"), messages.get(2));
        } finally {
            given().when().delete(BASE_PATH + "/functions/" + functionName);
            logs("DeleteLogGroup", "{\"logGroupName\":\"" + logGroup + "\"}");
        }
    }

    @Test
    void anInvokeAskingForItsLogTailIsAnsweredTheFunctionsLineBetweenLambdasLines() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "log-tail-" + suffix;
        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "nodejs20.x",
                      "Role": "%s",
                      "Handler": "index.handler",
                      "Timeout": 10,
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(functionName, ROLE, zipBase64(
                        "exports.handler = async () => { console.log('probe-line'); return { ok: true }; };\n")))
        .when()
                .post(BASE_PATH + "/functions")
        .then()
                .statusCode(201);
        try {
            String tail = given()
                    .header("X-Amz-Log-Type", "Tail")
                    .body("{}")
            .when()
                    .post(BASE_PATH + "/functions/" + functionName + "/invocations")
            .then()
                    .statusCode(200)
                    .extract().header("X-Amz-Log-Result");
            String log = new String(Base64.getDecoder().decode(tail), StandardCharsets.UTF_8);
            assertTrue(log.startsWith("START RequestId: "), log);
            assertTrue(log.contains("probe-line"), log);
            assertTrue(log.contains("\nEND RequestId: "), log);
            assertTrue(log.contains("\nREPORT RequestId: "), log);
        } finally {
            given().when().delete(BASE_PATH + "/functions/" + functionName);
        }
    }

    private static List<String> awaitMessages(String logGroup, int count) throws InterruptedException {
        List<String> messages = List.of();
        for (int attempt = 0; attempt < 100 && messages.size() < count; attempt++) {
            messages = logs("FilterLogEvents", "{\"logGroupName\":\"" + logGroup + "\"}")
                    .jsonPath().getList("events.message", String.class);
            if (messages == null) {
                messages = List.of();
            }
            if (messages.size() < count) {
                Thread.sleep(100);
            }
        }
        assertEquals(count, messages.size(), messages.toString());
        return messages;
    }

    private static Response logs(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(body)
        .when()
                .post("/");
    }

    private static String silentZipBase64() throws Exception {
        return zipBase64("exports.handler = async () => ({ ok: true });\n");
    }

    private static String zipBase64(String source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            return false;
        }
    }
}
