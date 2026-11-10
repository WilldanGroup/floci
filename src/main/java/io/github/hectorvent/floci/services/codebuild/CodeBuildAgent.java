package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Floci hands CodeBuild's local agent, the image AWS publishes for running a build as CodeBuild
 * runs it ("Run builds locally with the AWS CodeBuild agent", AWS CodeBuild User Guide), and what it
 * reads back.
 *
 * <p>The agent is the image {@code codebuild_build.sh} starts with the Docker socket, {@code IMAGE_NAME},
 * {@code SOURCE}, {@code ARTIFACTS}, {@code BUILDSPEC} and {@code INITIATOR}. Its entry point writes a
 * docker-compose file of two services, {@code agent}, the agent proper, and {@code build}, a container of
 * {@code IMAGE_NAME} in which CodeBuild's executor runs the buildspec's phases and prints the build's log
 * through the agent, and brings them up. Floci adds a compose file of its own after the agent's, through
 * a {@code docker-compose} ahead of the agent's on its {@code PATH}, so the two services join Floci's
 * network and carry Floci's labels, and the build is handed its variables and the buildspec Floci
 * resolved.
 */
final class CodeBuildAgent {

    /** The x86_64 agent, {@code public.ecr.aws/codebuild/local-builds:latest}, by the digest AWS publishes. */
    static final String X86_64_IMAGE =
            "public.ecr.aws/codebuild/local-builds@sha256:ccb19bdd7af94e4dc761e4c58c267e9455c28ec68d938086b4dc1cf8fe6b0940"; // partition-literal: AWS publishes the agent in ECR Public alone

    /** The ARM agent, {@code public.ecr.aws/codebuild/local-builds:aarch64}, by the digest AWS publishes. */
    static final String AARCH64_IMAGE =
            "public.ecr.aws/codebuild/local-builds@sha256:7d7b5d35d2ac4e062ae7ba8c662ffed15229a52d09bd0d664a7816c439679192"; // partition-literal: AWS publishes the agent in ECR Public alone

    static final String X86_64 = "x86_64";
    static final String AARCH64 = "aarch64";

    /** Where the agent's container finds Floci's compose file. */
    static final String OVERRIDE_PATH = "/LocalBuild/floci/docker-compose.override.yml";

    /** Ahead of the agent's own {@code /usr/local/bin/docker-compose} on its {@code PATH}. */
    static final String WRAPPER_PATH = "/usr/local/sbin/docker-compose";

    /**
     * Where the build's container finds the buildspec. The agent reads a {@code BUILDSPEC} outside
     * {@code SOURCE} at its own path in the build's container, so a buildspec Floci resolved never takes
     * the place of a file of the source.
     */
    static final String BUILDSPEC_PATH = "/codebuild/floci/buildspec.yml";

    /** The prefix the agent's own environment file gives each variable it hands the build. */
    static final String VARIABLE_PREFIX = "CUSTOMER_";

    /** The one variable the agent's environment file also sets on the build's container itself. */
    static final String RELATIVE_URI = "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI";

    /**
     * Runs the agent's own docker-compose with Floci's file after the agent's, on every call the agent
     * makes ({@code config}, {@code down}, {@code up}), so its validation sees what runs.
     */
    static final String COMPOSE_WRAPPER = """
            #!/bin/sh
            if [ "$1" = "-f" ] && [ -n "$2" ]; then
              file="$2"
              shift 2
              exec /usr/local/bin/docker-compose -f "$file" -f %s "$@"
            fi
            exec /usr/local/bin/docker-compose "$@"
            """.formatted(OVERRIDE_PATH);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    private static final Pattern AGENT_SERVICE_LINE = Pattern.compile("^agent-\\d+\\s*\\|\\s?(.*)$");

    private CodeBuildAgent() {
    }

    /** The architecture CodeBuild runs an environment type's builds on, or empty for one it does not name. */
    static Optional<String> architectureOf(String environmentType) {
        if (environmentType == null) {
            return Optional.empty();
        }
        return switch (environmentType) {
            case "ARM_CONTAINER", "ARM_LAMBDA_CONTAINER", "ARM_EC2", "MAC_ARM" -> Optional.of(AARCH64);
            case "LINUX_CONTAINER", "LINUX_GPU_CONTAINER", "LINUX_LAMBDA_CONTAINER", "LINUX_EC2",
                 "WINDOWS_CONTAINER", "WINDOWS_SERVER_2019_CONTAINER", "WINDOWS_SERVER_2022_CONTAINER",
                 "WINDOWS_EC2" -> Optional.of(X86_64);
            default -> Optional.empty();
        };
    }

    /** A Docker daemon's or a JVM's word for an architecture, in the agent's. */
    static String normalizeArchitecture(String architecture) {
        String lowered = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
        return switch (lowered) {
            case "aarch64", "arm64" -> AARCH64;
            case "x86_64", "amd64" -> X86_64;
            default -> lowered;
        };
    }

    /** The agent AWS publishes for an architecture, or empty where it publishes none. */
    static Optional<String> imageFor(String architecture) {
        return switch (architecture) {
            case AARCH64 -> Optional.of(AARCH64_IMAGE);
            case X86_64 -> Optional.of(X86_64_IMAGE);
            default -> Optional.empty();
        };
    }

    /**
     * Why this host runs no build of an environment type, or empty where it does: CodeBuild runs a build on
     * a host of its environment's architecture, and Floci runs nothing emulated.
     */
    static Optional<String> architectureRefusal(String projectName, String environmentType, String hostArchitecture) {
        return architectureOf(environmentType)
                .filter(wanted -> !wanted.equals(hostArchitecture))
                .map(wanted -> "Project " + projectName + " builds on " + environmentType + ", which is " + wanted
                        + ", and this Docker host is " + hostArchitecture
                        + ": a build runs on a host of its own architecture and is not emulated");
    }

    /**
     * The folder each build's working folder is made in: the setting where one is given, else
     * {@code floci-codebuild} under {@code $SLUICE_TMP} where that is set, else
     * {@code /tmp/floci-codebuild-<port>}. The agent binds what is in it from the host, so it must be the
     * same path on the host as in Floci's container.
     */
    static Path workspace(Optional<String> configured, Optional<String> sluiceTmp, int port) {
        return configured.filter(value -> !value.isBlank()).map(Path::of)
                .or(() -> sluiceTmp.filter(value -> !value.isBlank()).map(value -> Path.of(value, "floci-codebuild")))
                .orElseGet(() -> Path.of("/tmp", "floci-codebuild-" + port));
    }

    /**
     * The compose project of one build: the agent names its project after its working folder, so every
     * build would share one, and its {@code down -v} at each start would take down another's.
     */
    static String composeProject(String buildFolderName) {
        String sanitized = buildFolderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return "floci-codebuild-" + sanitized;
    }

    /**
     * What the build's services are given beyond the agent's own file.
     *
     * @param variables        each variable the build's commands read, by name, handed as the agent's
     *                         environment file hands them, so a value may hold a newline or a {@code $}
     * @param network          the user-defined network both services join, or empty for compose's own
     * @param dnsServers       the build container's resolvers
     * @param extraHosts       the build container's {@code host:ip} entries
     * @param labels           Floci's labels for both containers and the project's volumes
     * @param buildspec        the buildspec's path on the Docker host
     */
    record Plan(Map<String, String> variables, Optional<String> network,
                List<String> dnsServers, List<String> extraHosts, Map<String, String> labels, Path buildspec) {
    }

    /** Floci's compose file for one build, with every {@code $} escaped from compose's interpolation. */
    static String composeOverride(Plan plan) {
        Map<String, Object> agentEnvironment = new LinkedHashMap<>();
        Map<String, Object> buildEnvironment = new LinkedHashMap<>();
        plan.variables().forEach((name, value) -> {
            agentEnvironment.put(VARIABLE_PREFIX + name, literal(value));
            if (RELATIVE_URI.equals(name)) {
                buildEnvironment.put(name, literal(value));
            }
        });

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("labels", literals(plan.labels()));
        agent.put("environment", agentEnvironment);

        Map<String, Object> build = new LinkedHashMap<>();
        build.put("labels", literals(plan.labels()));
        if (!buildEnvironment.isEmpty()) {
            build.put("environment", buildEnvironment);
        }
        build.put("volumes", List.of(literal(plan.buildspec().toString()) + ":" + BUILDSPEC_PATH + ":ro"));
        if (!plan.dnsServers().isEmpty()) {
            build.put("dns", plan.dnsServers().stream().map(CodeBuildAgent::literal).toList());
        }
        if (!plan.extraHosts().isEmpty()) {
            build.put("extra_hosts", plan.extraHosts().stream().map(CodeBuildAgent::literal).toList());
        }

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("agent", agent);
        services.put("build", build);

        Map<String, Object> volumeLabels = Map.of("labels", literals(plan.labels()));
        Map<String, Object> volumes = new LinkedHashMap<>();
        volumes.put("source_volume", volumeLabels);
        volumes.put("user_volume", volumeLabels);

        Map<String, Object> override = new LinkedHashMap<>();
        override.put("services", services);
        override.put("volumes", volumes);
        plan.network().ifPresent(network -> override.put("networks",
                Map.of("default", Map.of("name", literal(network), "external", true))));
        try {
            return YAML.writeValueAsString(override);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the agent's compose file", e);
        }
    }

    private static Map<String, Object> literals(Map<String, String> values) {
        Map<String, Object> escaped = new LinkedHashMap<>();
        values.forEach((key, value) -> escaped.put(key, literal(value)));
        return escaped;
    }

    /** Compose reads {@code $NAME} in a value as a variable of its own, and {@code $$} as one {@code $}. */
    private static String literal(String value) {
        return value == null ? "" : value.replace("$", "$$");
    }

    /**
     * The build's log line in one line of the agent's output, or empty for a line of docker-compose's own:
     * the executor writes the build's log through the agent's service, whose lines compose prefixes with
     * its name.
     */
    static Optional<String> buildLogLine(String agentOutputLine) {
        Matcher matcher = AGENT_SERVICE_LINE.matcher(agentOutputLine);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
