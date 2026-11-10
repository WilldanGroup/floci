package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Floci hands CodeBuild's local agent beside its own compose file, and what it reads back from the
 * agent's output.
 */
class CodeBuildAgentTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void eachVariableReachesTheBuildAsTheAgentsEnvironmentFileHandsItEscapedFromComposeInterpolation()
            throws Exception {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("TOKEN", "a$HOME b$$c");
        variables.put("PEM", "line one\nline two");
        variables.put("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "/v2/credentials/abc");

        JsonNode override = YAML.readTree(CodeBuildAgent.composeOverride(plan(variables, Optional.of("floci-net"))));

        JsonNode agent = override.path("services").path("agent").path("environment");
        assertEquals("a$$HOME b$$$$c", agent.path("CUSTOMER_TOKEN").asText());
        assertEquals("line one\nline two", agent.path("CUSTOMER_PEM").asText());
        assertEquals("/v2/credentials/abc", override.path("services").path("build").path("environment")
                .path("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI").asText());
        assertFalse(override.path("services").path("build").path("environment").has("TOKEN"));
    }

    @Test
    void bothServicesJoinTheUserDefinedNetworkAndTheBuildReadsTheBuildspecFromFlocisFolder() throws Exception {
        JsonNode override = YAML.readTree(CodeBuildAgent.composeOverride(plan(Map.of(), Optional.of("floci-net"))));

        assertEquals("floci-net", override.path("networks").path("default").path("name").asText());
        assertTrue(override.path("networks").path("default").path("external").asBoolean());
        assertEquals("/tmp/w/b-1/buildspec.yml:" + CodeBuildAgent.BUILDSPEC_PATH + ":ro",
                override.path("services").path("build").path("volumes").get(0).asText());
        assertEquals("true", override.path("services").path("agent").path("labels").path("floci").asText());
        assertEquals("true", override.path("volumes").path("user_volume").path("labels").path("floci").asText());
    }

    @Test
    void withNoUserDefinedNetworkComposeKeepsItsOwn() throws Exception {
        JsonNode override = YAML.readTree(CodeBuildAgent.composeOverride(plan(Map.of(), Optional.empty())));

        assertTrue(override.path("networks").isMissingNode(), override::toString);
    }

    @Test
    void aProjectOfTheOtherArchitectureIsRefusedNamingBoth() {
        String refusal = CodeBuildAgent.architectureRefusal("p", "LINUX_CONTAINER", CodeBuildAgent.AARCH64)
                .orElseThrow();

        assertTrue(refusal.contains(CodeBuildAgent.X86_64) && refusal.contains(CodeBuildAgent.AARCH64), refusal);
        assertEquals(Optional.empty(),
                CodeBuildAgent.architectureRefusal("p", "ARM_CONTAINER", CodeBuildAgent.AARCH64));
        assertEquals(CodeBuildAgent.AARCH64, CodeBuildAgent.normalizeArchitecture("arm64"));
        assertEquals(CodeBuildAgent.X86_64, CodeBuildAgent.normalizeArchitecture("amd64"));
    }

    @Test
    void theWorkspaceIsTheSettingElseUnderSluiceTmpElseNamedForFlocisPort() {
        assertEquals(Path.of("/w"), CodeBuildAgent.workspace(Optional.of("/w"), Optional.of("/s"), 4566));
        assertEquals(Path.of("/s/floci-codebuild"), CodeBuildAgent.workspace(Optional.empty(), Optional.of("/s"), 4566));
        assertEquals(Path.of("/tmp/floci-codebuild-4567"),
                CodeBuildAgent.workspace(Optional.empty(), Optional.empty(), 4567));
    }

    @Test
    void theBuildsLogIsTheAgentServicesLinesWithoutComposesPrefix() {
        assertEquals(Optional.of("[Container] 2026/11/10 02:00:42.409202 Phase complete: BUILD State: FAILED"),
                CodeBuildAgent.buildLogLine(
                        "agent-1  | [Container] 2026/11/10 02:00:42.409202 Phase complete: BUILD State: FAILED"));
        assertEquals(Optional.of(""), CodeBuildAgent.buildLogLine("agent-1  | "));
        assertEquals(Optional.empty(), CodeBuildAgent.buildLogLine(" Container p-build-1  Created"));
        assertEquals(Optional.empty(), CodeBuildAgent.buildLogLine("build-1 exited with code 0"));
    }

    @Test
    void theAgentIsHandedTheBuildspecWithoutTheSectionsFlociResolvedAndEveryOtherLineAsWritten() {
        String buildspec = """
                version: 0.2
                env:
                  shell: bash
                  variables:
                    KEEP: "yes"
                  parameter-store:
                    TOKEN: /a/b
                    OTHER: /c
                  secrets-manager:
                    SECRET: s:key
                  exported-variables:
                    - KEEP
                phases:
                  build:
                    commands:
                      - |
                        echo "$KEEP"
                """;

        String handed = BuildspecParser.withoutResolvedVariables(buildspec);

        assertEquals("""
                version: 0.2
                env:
                  shell: bash
                  variables:
                    KEEP: "yes"
                  exported-variables:
                    - KEEP
                phases:
                  build:
                    commands:
                      - |
                        echo "$KEEP"
                """, handed.stripTrailing() + "\n");
        String nothingResolved = buildspec.replace("  parameter-store:\n    TOKEN: /a/b\n    OTHER: /c\n"
                + "  secrets-manager:\n    SECRET: s:key\n", "");
        assertEquals(nothingResolved, BuildspecParser.withoutResolvedVariables(nothingResolved));
    }

    private static CodeBuildAgent.Plan plan(Map<String, String> variables, Optional<String> network) {
        return new CodeBuildAgent.Plan(variables, network, List.of("10.0.0.2"), List.of("host.docker.internal:host-gateway"),
                Map.of("floci", "true"), Path.of("/tmp/w/b-1/buildspec.yml"));
    }
}
