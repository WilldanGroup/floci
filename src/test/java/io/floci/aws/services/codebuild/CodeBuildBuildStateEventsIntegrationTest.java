package io.floci.aws.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a rule on the default bus receives as a build's state changes: one {@code CodeBuild Build State
 * Change} as the build starts, {@code IN_PROGRESS}, and one as it ends, with the status it ended with, in
 * the shape of "Build notifications input format reference" (AWS CodeBuild User Guide), on the rule its
 * example pattern describes.
 */
@QuarkusTest
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class CodeBuildBuildStateEventsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String DETAIL_TYPE = "CodeBuild Build State Change";

    @Inject
    DockerClient dockerClient;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String projectName = "events-" + suffix;

    @BeforeEach
    void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void removeWhatTheTestMade() {
        codeBuild("DeleteProject").body(Map.of("name", projectName)).post("/");
    }

    @Test
    void aBuildThatFailsSendsItsStartAndItsFailureToARuleOnTheDefaultBus() throws Exception {
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", projectName, pattern())) {
            createProject(null);
            String buildArn = startBuild();

            List<JsonNode> events = awaitStatus(queue, "FAILED");

            assertEquals(List.of("IN_PROGRESS", "FAILED"), statuses(events));
            JsonNode started = events.get(0);
            assertEquals("aws.codebuild", started.path("source").asText());
            assertEquals(DETAIL_TYPE, started.path("detail-type").asText());
            assertEquals("000000000000", started.path("account").asText());
            assertEquals("us-east-1", started.path("region").asText());
            assertEquals(List.of(buildArn), texts(started.path("resources")));
            assertEquals(buildArn, started.path("detail").path("build-id").asText());
            assertEquals(projectName, started.path("detail").path("project-name").asText());
            assertFalse(started.path("detail").path("additional-information").path("build-complete").asBoolean());

            JsonNode ended = events.get(1).path("detail");
            assertTrue(ended.path("additional-information").path("build-complete").asBoolean(), ended::toString);
            assertEquals("COMPLETED", ended.path("current-phase").asText());
            assertEquals("1", ended.path("version").asText());
            assertEquals("LINUX_CONTAINER",
                    ended.path("additional-information").path("environment").path("type").asText());
            List<String> phases = new ArrayList<>();
            ended.path("additional-information").path("phases").forEach(p -> phases.add(
                    p.path("phase-type").asText() + "=" + p.path("phase-status").asText()));
            assertTrue(phases.contains("DOWNLOAD_SOURCE=FAILED"), phases::toString);
        }
    }

    @Test
    void aBuildThatSucceedsSendsItsStartAndItsSuccess() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient), "Docker daemon must be available");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", projectName, pattern())) {
            createProject("version: 0.2\nphases:\n  build:\n    commands:\n      - echo done\n");
            String buildArn = startBuild();

            List<JsonNode> events = awaitStatus(queue, "SUCCEEDED");

            assertEquals(List.of("IN_PROGRESS", "SUCCEEDED"), statuses(events));
            JsonNode ended = events.get(1).path("detail");
            assertEquals(buildArn, ended.path("build-id").asText());
            assertEquals("/aws/codebuild/" + projectName,
                    ended.path("additional-information").path("logs").path("group-name").asText());
            JsonNode phases = ended.path("additional-information").path("phases");
            assertEquals("COMPLETED", phases.get(phases.size() - 1).path("phase-type").asText());
        }
    }

    /** The example pattern of "Build notifications sample", for this test's project alone. */
    private String pattern() {
        return """
                {"source": ["aws.codebuild"], "detail-type": ["%s"],
                 "detail": {"build-status": ["IN_PROGRESS", "SUCCEEDED", "FAILED", "STOPPED"],
                            "project-name": ["%s"]}}
                """.formatted(DETAIL_TYPE, projectName);
    }

    /** The events on the queue until one carries {@code status}, in the order their builds sent them. */
    private static List<JsonNode> awaitStatus(EventRuleQueue queue, String status) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (statuses(events).stream().noneMatch(status::equals) && System.nanoTime() < deadline) {
            events.addAll(queue.drain());
            Thread.sleep(250);
        }
        events.sort((a, b) -> Boolean.compare(
                a.path("detail").path("additional-information").path("build-complete").asBoolean(),
                b.path("detail").path("additional-information").path("build-complete").asBoolean()));
        return events;
    }

    private static List<String> statuses(List<JsonNode> events) {
        return events.stream().map(e -> e.path("detail").path("build-status").asText()).toList();
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    /** A project of {@code buildspec}, or of none at all, which fails its build in DOWNLOAD_SOURCE. */
    private void createProject(String buildspec) {
        Map<String, Object> source = new HashMap<>(Map.of("type", "NO_SOURCE"));
        if (buildspec != null) {
            source.put("buildspec", buildspec);
        }
        Map<String, Object> project = new HashMap<>();
        project.put("name", projectName);
        project.put("source", source);
        project.put("artifacts", Map.of("type", "NO_ARTIFACTS"));
        project.put("environment", Map.of("type", "LINUX_CONTAINER", "image", TestImages.BUSYBOX,
                "computeType", "BUILD_GENERAL1_SMALL"));
        project.put("serviceRole", "arn:aws:iam::000000000000:role/" + projectName);
        codeBuild("CreateProject").body(project).post("/").then().statusCode(200);
    }

    private String startBuild() {
        return codeBuild("StartBuild").body(Map.of("projectName", projectName)).post("/")
                .then().statusCode(200).extract().jsonPath().getString("build.arn");
    }

    private static RequestSpecification codeBuild(String action) {
        return given().header("X-Amz-Target", "CodeBuild_20161006." + action).contentType(CONTENT_TYPE);
    }
}
