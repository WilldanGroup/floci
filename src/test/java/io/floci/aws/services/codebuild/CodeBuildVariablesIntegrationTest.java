package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code PARAMETER_STORE} and {@code SECRETS_MANAGER} variable, the project's, the start's and the
 * buildspec's, reaches the build as the value its parameter or secret holds, read as the project's
 * service role with IAM enforcement on; one the role may not read, or that does not stand, fails the
 * build in {@code DOWNLOAD_SOURCE} before any of its buildspec runs, naming the variable, with the
 * service's code and words.
 *
 * <p>The build that reads the values runs in CodeBuild's local agent, skipped without a Docker daemon;
 * the refusals start no agent.
 */
@QuarkusTest
@TestProfile(CodeBuildVariablesIntegrationTest.Profile.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class CodeBuildVariablesIntegrationTest {

    private static final String JSON = "application/x-amz-json-1.1";
    private static final String ADMIN = "test";
    private static final String REGION = "us-east-1";

    @Inject
    DockerClient dockerClient;

    @Inject
    IamService iamService;

    @Inject
    S3Service s3Service;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String roleName = "variables-" + suffix;
    private final String projectName = "variables-" + suffix;
    private final String allowed = "/allowed-" + suffix;
    private final String denied = "/denied-" + suffix;
    private final String resultsBucket = "variables-" + suffix;
    private final List<String> secrets = new ArrayList<>();
    private String roleArn;
    private String allowedSecretArn;

    @BeforeEach
    void createTheRoleItsParametersAndSecrets() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        putParameter(allowed + "/project", "from-project");
        putParameter(allowed + "/start", "from-start");
        putParameter(allowed + "/spec", "from-spec");
        putParameter(denied + "/x", "never-read");
        allowedSecretArn = createSecret("allowed-" + suffix, "{\"key\":\"from-secret-key\",\"other\":\"x\"}");
        createSecret("denied-" + suffix, "never-read");
        roleArn = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"codebuild.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 0, null).getArn();
        iamService.putRolePolicy(roleName, "ReadAllowed", """
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"ssm:GetParameters",
                 "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s/*"},
                {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s"}]}
                """.formatted(allowed, allowedSecretArn));
    }

    @AfterEach
    void removeWhatTheTestMade() {
        codeBuild("DeleteProject").body(Map.of("name", projectName)).post("/");
        for (String name : List.of(allowed + "/project", allowed + "/start", allowed + "/spec", denied + "/x")) {
            call("ssm", "AmazonSSM.DeleteParameter", Map.of("Name", name));
        }
        for (String secret : secrets) {
            call("secretsmanager", "secretsmanager.DeleteSecret",
                    Map.of("SecretId", secret, "ForceDeleteWithoutRecovery", true));
        }
        try {
            s3Service.listObjects(resultsBucket, null, null, 1000)
                    .forEach(o -> s3Service.deleteObject(resultsBucket, o.getKey()));
            s3Service.deleteBucket(resultsBucket);
        } catch (RuntimeException ignored) {
            // A bucket the test never got to make.
        }
        try {
            iamService.deleteRolePolicy(roleName, "ReadAllowed");
            iamService.deleteRole(roleName);
        } catch (RuntimeException ignored) {
            // A role the test never got to make.
        }
    }

    @Test
    void eachParameterAndSecretReachesTheBuildAsItsValueReadAsTheProjectsRole() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient), "Docker daemon must be available");
        given().header("Authorization", auth(ADMIN, "s3")).put("/" + resultsBucket).then().statusCode(200);
        createProject("""
                version: 0.2
                env:
                  parameter-store:
                    SPEC_PARAMETER: %s/spec
                  secrets-manager:
                    SPEC_SECRET: allowed-%s:key
                phases:
                  build:
                    commands:
                      - printf '%%s|' "$PROJECT_PARAMETER" "$START_PARAMETER" "$SPEC_PARAMETER" "$PROJECT_SECRET" "$SPEC_SECRET" "$PLAIN" > values.txt
                artifacts:
                  files:
                    - values.txt
                """.formatted(allowed, suffix), List.of(
                variable("PROJECT_PARAMETER", "PARAMETER_STORE", allowed + "/project"),
                variable("PROJECT_SECRET", "SECRETS_MANAGER", "allowed-" + suffix),
                variable("PLAIN", "PLAINTEXT", "plain $HOME")),
                Map.of("type", "S3", "location", resultsBucket, "packaging", "NONE"));

        JsonPath build = awaitBuild(startBuild(List.of(
                variable("START_PARAMETER", "PARAMETER_STORE", allowed + "/start"))));

        assertEquals("SUCCEEDED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases"));
        assertEquals("from-project|from-start|from-spec|{\"key\":\"from-secret-key\",\"other\":\"x\"}|"
                + "from-secret-key|plain $HOME|", result("values.txt"));
    }

    @Test
    void aProjectParameterTheRoleMayNotReadFailsTheBuildBeforeItsBuildspecNamingTheVariable() throws Exception {
        createProject(echoBuildspec(), List.of(variable("DENIED", "PARAMETER_STORE", denied + "/x")),
                Map.of("type", "NO_ARTIFACTS"));

        String message = refusal(awaitBuild(startBuild(List.of())));

        assertTrue(message.contains("DENIED") && message.contains("AccessDeniedException")
                && message.contains("ssm:GetParameters") && message.contains("AWSCodeBuild-"), message);
    }

    @Test
    void aStartParameterThatDoesNotStandFailsTheBuildNamingTheVariable() throws Exception {
        createProject(echoBuildspec(), List.of(), Map.of("type", "NO_ARTIFACTS"));

        String message = refusal(awaitBuild(startBuild(List.of(
                variable("MISSING", "PARAMETER_STORE", allowed + "/missing")))));

        assertTrue(message.contains("MISSING") && message.contains("ParameterNotFound"), message);
    }

    @Test
    void aBuildspecParameterTheRoleMayNotReadFailsTheBuildNamingTheVariable() throws Exception {
        createProject("""
                version: 0.2
                env:
                  parameter-store:
                    SPEC_DENIED: %s/x
                phases:
                  build:
                    commands:
                      - echo "$SPEC_DENIED"
                """.formatted(denied), List.of(), Map.of("type", "NO_ARTIFACTS"));

        String message = refusal(awaitBuild(startBuild(List.of())));

        assertTrue(message.contains("SPEC_DENIED") && message.contains("AccessDeniedException"), message);
    }

    @Test
    void aProjectSecretTheRoleMayNotReadFailsTheBuildNamingTheVariable() throws Exception {
        createProject(echoBuildspec(), List.of(variable("DENIED_SECRET", "SECRETS_MANAGER", "denied-" + suffix)),
                Map.of("type", "NO_ARTIFACTS"));

        String message = refusal(awaitBuild(startBuild(List.of())));

        assertTrue(message.contains("DENIED_SECRET") && message.contains("AccessDeniedException")
                && message.contains("secretsmanager:GetSecretValue"), message);
    }

    @Test
    void aBuildspecSecretThatDoesNotStandFailsTheBuildNamingTheVariable() throws Exception {
        createProject("""
                version: 0.2
                env:
                  secrets-manager:
                    SPEC_MISSING: missing-%s
                phases:
                  build:
                    commands:
                      - echo "$SPEC_MISSING"
                """.formatted(suffix), List.of(), Map.of("type", "NO_ARTIFACTS"));

        String message = refusal(awaitBuild(startBuild(List.of())));

        assertTrue(message.contains("SPEC_MISSING") && message.contains("ResourceNotFoundException"), message);
    }

    /** The build failed in DOWNLOAD_SOURCE, its last phase, and this is the context it failed with. */
    private static String refusal(JsonPath build) {
        assertEquals("FAILED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases"));
        List<String> phaseTypes = build.getList("phases.phaseType");
        assertEquals("DOWNLOAD_SOURCE", phaseTypes.get(phaseTypes.size() - 1), phaseTypes::toString);
        assertEquals("Decrypted Variables Error",
                build.getString("phases.find { it.phaseType == 'DOWNLOAD_SOURCE' }.contexts[0].statusCode"));
        return build.getString("phases.find { it.phaseType == 'DOWNLOAD_SOURCE' }.contexts[0].message");
    }

    private static String echoBuildspec() {
        return "version: 0.2\nphases:\n  build:\n    commands:\n      - echo never\n";
    }

    private static Map<String, String> variable(String name, String type, String value) {
        return Map.of("name", name, "type", type, "value", value);
    }

    private String hostEnvironmentType() {
        String architecture;
        try {
            architecture = dockerClient.infoCmd().exec().getArchitecture();
        } catch (RuntimeException e) {
            architecture = System.getProperty("os.arch");
        }
        return CodeBuildAgent.AARCH64.equals(CodeBuildAgent.normalizeArchitecture(architecture))
                ? "ARM_CONTAINER" : "LINUX_CONTAINER";
    }

    private void createProject(String buildspec, List<Map<String, String>> variables, Map<String, String> artifacts) {
        Map<String, Object> project = new HashMap<>();
        project.put("name", projectName);
        project.put("source", Map.of("type", "NO_SOURCE", "buildspec", buildspec));
        project.put("artifacts", artifacts);
        project.put("environment", Map.of("type", hostEnvironmentType(), "image", TestImages.DEBIAN_SLIM,
                "computeType", "BUILD_GENERAL1_SMALL", "environmentVariables", variables));
        project.put("serviceRole", roleArn);
        codeBuild("CreateProject").body(project).post("/").then().statusCode(200);
    }

    private String startBuild(List<Map<String, String>> overrides) {
        Map<String, Object> start = new HashMap<>(Map.of("projectName", projectName));
        if (!overrides.isEmpty()) {
            start.put("environmentVariablesOverride", overrides);
        }
        return codeBuild("StartBuild").body(start).post("/")
                .then().statusCode(200).extract().jsonPath().getString("build.id");
    }

    private JsonPath awaitBuild(String buildId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        while (true) {
            JsonPath build = codeBuild("BatchGetBuilds").body(Map.of("ids", List.of(buildId))).post("/")
                    .then().statusCode(200).extract().jsonPath().setRootPath("builds[0]");
            if (build.getBoolean("buildComplete") || System.nanoTime() > deadline) {
                return build;
            }
            Thread.sleep(250);
        }
    }

    private void putParameter(String name, String value) {
        call("ssm", "AmazonSSM.PutParameter", Map.of("Name", name, "Value", value, "Type", "SecureString"))
                .then().statusCode(200);
    }

    private String createSecret(String name, String value) {
        secrets.add(name);
        return call("secretsmanager", "secretsmanager.CreateSecret", Map.of("Name", name, "SecretString", value))
                .then().statusCode(200).extract().jsonPath().getString("ARN");
    }

    private static Response call(String service, String target, Map<String, Object> body) {
        return given().header("Authorization", auth(ADMIN, REGION, service)).header("X-Amz-Target", target)
                .contentType(JSON).body(body).post("/");
    }

    /** A CodeBuild call signed as the account's administrator, whom IAM enforcement lets do anything. */
    private static RequestSpecification codeBuild(String action) {
        return given().header("Authorization", auth(ADMIN, "codebuild"))
                .header("X-Amz-Target", "CodeBuild_20161006." + action).contentType(JSON);
    }

    private String result(String key) {
        try {
            return new String(s3Service.getObject(resultsBucket, key).getData(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new AssertionError("the build left no " + key + " in " + resultsBucket, e);
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
