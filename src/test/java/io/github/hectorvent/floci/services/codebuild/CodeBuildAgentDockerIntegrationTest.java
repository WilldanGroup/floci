package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.core.common.FileTrees;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build runs in CodeBuild's local agent of the Docker host's architecture, as CodeBuild runs it: the
 * agent runs the buildspec's phases under the shell it names, the agent's containers join Floci's network,
 * a project of the other architecture is refused before any agent starts, and nothing the build made is
 * left once it has ended, neither a container, a volume nor its working folder.
 *
 * <p>Pulls the agent AWS publishes for this host's architecture on first use. Skipped without a Docker
 * daemon, like the other {@code *DockerIntegrationTest} classes.
 */
@QuarkusTest
@TestProfile(CodeBuildAgentDockerIntegrationTest.Profile.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class CodeBuildAgentDockerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String RESOURCE_ID_LABEL = "io.floci.resource-id";
    private static final String AGENT_RUNNER = "the agent Floci starts";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerDetector containerDetector;

    @Inject
    CurrentContainerNetworkResolver currentContainerNetworkResolver;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String projectName = "agent-" + suffix;
    private final String roleName = "agent-" + suffix;
    private final String resultsBucket = "agent-results-" + suffix;
    private String roleArn;

    @BeforeEach
    void requireDockerAndARole() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient), "Docker daemon must be available");
        RestAssuredJsonUtils.configureAwsContentTypes();
        roleArn = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"codebuild.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 0, null).getArn();
    }

    @AfterEach
    void removeWhatTheTestMade() {
        codeBuild("DeleteProject").body(Map.of("name", projectName)).post("/");
        try {
            s3Service.listObjects(resultsBucket, null, null, 1000)
                    .forEach(o -> s3Service.deleteObject(resultsBucket, o.getKey()));
            s3Service.deleteBucket(resultsBucket);
        } catch (RuntimeException ignored) {
            // A bucket the test never got to make.
        }
        try {
            iamService.deleteRole(roleName);
        } catch (RuntimeException ignored) {
            // A role the test never got to make.
        }
    }

    @AfterAll
    static void removeTheWorkspace() throws IOException {
        if (Files.exists(Profile.WORKSPACE)) {
            FileTrees.delete(Profile.WORKSPACE);
        }
    }

    @Test
    void aBuildspecThatAsksForBashRunsItsCommandsUnderBash() throws Exception {
        given().put("/" + resultsBucket).then().statusCode(200);
        createProject(hostEnvironmentType(), """
                version: 0.2
                env:
                  shell: bash
                phases:
                  build:
                    commands:
                      - set -euo pipefail; if [[ "$BASH_VERSION" == 5* ]]; then echo "bash $BASH_VERSION" > shell.txt; fi
                artifacts:
                  files:
                    - shell.txt
                """, Map.of("type", "S3", "location", resultsBucket, "packaging", "NONE"));

        String buildId = startBuild();
        Map<String, Container> seen = new HashMap<>();
        JsonPath build = awaitBuild(buildId, seen);
        Set<String> networks = networksOf(seen);

        assertEquals("SUCCEEDED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases"));
        assertTrue(result("shell.txt").startsWith("bash 5"), () -> result("shell.txt"));
        assertFalse(networks.isEmpty(), "no container of the build's agent was seen while it ran");
        assertFalse(networks.contains("bridge"), () -> "the agent's containers joined " + networks);
        if (containerDetector.isRunningInContainer()) {
            assertEquals(Set.of(currentContainerNetworkResolver.resolveNetworkName().orElseThrow()), networks);
        }
    }

    @Test
    void aProjectOfTheOtherArchitectureIsRefusedNamingBothBeforeAnyAgentStarts() throws Exception {
        String host = CodeBuildAgent.normalizeArchitecture(dockerClient.infoCmd().exec().getArchitecture());
        String other = CodeBuildAgent.AARCH64.equals(host) ? "LINUX_CONTAINER" : "ARM_CONTAINER";
        createProject(other, "version: 0.2\nphases:\n  build:\n    commands:\n      - echo never\n",
                Map.of("type", "NO_ARTIFACTS"));

        JsonPath build = awaitBuild(startBuild(), new HashMap<>());

        assertEquals("FAILED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases"));
        List<String> phaseTypes = build.getList("phases.phaseType");
        assertEquals("PROVISIONING", phaseTypes.get(phaseTypes.size() - 1), phaseTypes::toString);
        String message = build.getString("phases.find { it.phaseType == 'PROVISIONING' }.contexts[0].message");
        assertTrue(message.contains(CodeBuildAgent.AARCH64) && message.contains(CodeBuildAgent.X86_64), message);
    }

    @Test
    void everyContainerABuildStartsCarriesTheBuildsIdAndNoneIsLeftOnceItHasEnded() throws Exception {
        createProject(hostEnvironmentType(), "version: 0.2\nphases:\n  build:\n    commands:\n      - echo done\n",
                Map.of("type", "NO_ARTIFACTS"));

        String buildId = startBuild();
        Map<String, Container> seen = new HashMap<>();
        JsonPath build = awaitBuild(buildId, seen);

        assertEquals("SUCCEEDED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases"));
        // The agent Floci starts, and the agent, build and credentials relay its docker-compose starts,
        // the relay where the build's network is user-defined, each found by the build's id alone and
        // each with Floci's resource namespace and extra labels, as every container Floci starts.
        Set<String> expected = new TreeSet<>(Set.of(AGENT_RUNNER, "agent", "build"));
        if (containerDetector.isRunningInContainer() && currentContainerNetworkResolver.resolveNetworkName()
                .filter(network -> !Set.of("bridge", "default", "host", "none").contains(network)).isPresent()) {
            expected.add(CodeBuildAgent.RELAY_SERVICE);
        }
        assertEquals(expected, new TreeSet<>(seen.values().stream().map(CodeBuildAgentDockerIntegrationTest::role)
                .toList()));
        for (Container container : seen.values()) {
            assertEquals("codebuild", container.getLabels().get("io.floci.service"), () -> role(container));
            assertEquals(buildId, container.getLabels().get(RESOURCE_ID_LABEL), () -> role(container));
            assertEquals(Profile.NAMESPACE, container.getLabels().get("floci_namespace"), () -> role(container));
            assertEquals(Profile.EXTRA_LABEL_VALUE, container.getLabels().get(Profile.EXTRA_LABEL_KEY),
                    () -> role(container));
        }
        String label = RESOURCE_ID_LABEL + "=" + buildId;
        assertEquals(List.of(), dockerClient.listContainersCmd().withShowAll(true).withLabelFilter(List.of(label))
                .exec().stream().map(Container::getNames).toList());
        assertEquals(List.of(), Optional.ofNullable(dockerClient.listVolumesCmd()
                .withFilter("label", List.of(label)).exec().getVolumes()).orElse(List.of()));
        if (Files.exists(Profile.WORKSPACE)) {
            try (Stream<Path> left = Files.list(Profile.WORKSPACE)) {
                assertEquals(List.of(), left.toList());
            }
        }
    }

    private String hostEnvironmentType() {
        String host = CodeBuildAgent.normalizeArchitecture(dockerClient.infoCmd().exec().getArchitecture());
        return CodeBuildAgent.AARCH64.equals(host) ? "ARM_CONTAINER" : "LINUX_CONTAINER";
    }

    private void createProject(String environmentType, String buildspec, Map<String, String> artifacts) {
        Map<String, Object> project = new HashMap<>();
        project.put("name", projectName);
        project.put("source", Map.of("type", "NO_SOURCE", "buildspec", buildspec));
        project.put("artifacts", artifacts);
        project.put("environment", Map.of("type", environmentType, "image", TestImages.DEBIAN_SLIM,
                "computeType", "BUILD_GENERAL1_SMALL"));
        project.put("serviceRole", roleArn);
        codeBuild("CreateProject").body(project).post("/").then().statusCode(200);
    }

    private String startBuild() {
        return codeBuild("StartBuild").body(Map.of("projectName", projectName)).post("/")
                .then().statusCode(200).extract().jsonPath().getString("build.id");
    }

    /** Polls the build to its end, collecting the containers labelled with its id meanwhile, by their ids. */
    private JsonPath awaitBuild(String buildId, Map<String, Container> seen) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        String label = RESOURCE_ID_LABEL + "=" + buildId;
        while (true) {
            for (Container container : dockerClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(List.of(label)).exec()) {
                seen.put(container.getId(), container);
            }
            JsonPath build = codeBuild("BatchGetBuilds").body(Map.of("ids", List.of(buildId))).post("/")
                    .then().statusCode(200).extract().jsonPath().setRootPath("builds[0]");
            if (build.getBoolean("buildComplete") || System.nanoTime() > deadline) {
                return build;
            }
            Thread.sleep(250);
        }
    }

    /** The networks the containers are on, a container in another's namespace on none of its own. */
    private static Set<String> networksOf(Map<String, Container> containers) {
        Set<String> networks = new LinkedHashSet<>();
        for (Container container : containers.values()) {
            if (container.getNetworkSettings() != null && container.getNetworkSettings().getNetworks() != null) {
                networks.addAll(container.getNetworkSettings().getNetworks().keySet());
            }
        }
        return networks;
    }

    /** The compose service a container is, or {@link #AGENT_RUNNER} for the agent Floci starts. */
    private static String role(Container container) {
        String service = container.getLabels().get("com.docker.compose.service");
        return service != null ? service : AGENT_RUNNER;
    }

    private static RequestSpecification codeBuild(String action) {
        return given().header("X-Amz-Target", "CodeBuild_20161006." + action).contentType(CONTENT_TYPE);
    }

    private String result(String key) {
        try {
            return new String(s3Service.getObject(resultsBucket, key).getData(), StandardCharsets.UTF_8).strip();
        } catch (RuntimeException e) {
            throw new AssertionError("the build left no " + key + " in " + resultsBucket, e);
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        /** Fixed, as the profile and the test class are loaded apart and must name one folder. */
        static final Path WORKSPACE = Path.of("/tmp", "floci-codebuild-agent-test");
        static final String NAMESPACE = "codebuild-agent-test";
        static final String EXTRA_LABEL_KEY = "io.floci.test.checkout";
        static final String EXTRA_LABEL_VALUE = "codebuild-agent-test";

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.codebuild.agent-workspace", WORKSPACE.toString(),
                    "floci.docker.resource-namespace", NAMESPACE,
                    "floci.docker.extra-labels[0].key", EXTRA_LABEL_KEY,
                    "floci.docker.extra-labels[0].value", EXTRA_LABEL_VALUE);
        }
    }
}
