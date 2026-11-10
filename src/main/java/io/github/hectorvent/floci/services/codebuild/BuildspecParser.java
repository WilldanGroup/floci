package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless YAML/JSON buildspec parser. Supports version 0.2.
 */
class BuildspecParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> RESOLVED_SECTIONS = List.of("parameter-store", "secrets-manager");

    record ParsedBuildspec(
            Map<String, String> envVariables,
            Map<String, String> parameterStoreVars,
            Map<String, String> secretsManagerVars,
            List<String> installCommands,
            List<String> preBuildCommands,
            List<String> buildCommands,
            List<String> postBuildCommands,
            ParsedArtifacts artifacts
    ) {}

    record ParsedArtifacts(
            String type,
            List<String> files,
            String baseDirectory,
            boolean discardPaths,
            String name,
            String packaging
    ) {}

    static ParsedBuildspec parse(String content) {
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidInputException", "Buildspec content is empty", 400);
        }
        try {
            JsonNode root = YAML.readTree(content);

            JsonNode envNode = root.path("env");
            Map<String, String> envVars = parseStringMap(envNode.path("variables"));
            Map<String, String> paramStore = parseStringMap(envNode.path("parameter-store"));
            Map<String, String> secretsMgr = parseStringMap(envNode.path("secrets-manager"));

            JsonNode phases = root.path("phases");
            List<String> install = parseCommands(phases.path("install"));
            List<String> preBuild = parseCommands(phases.path("pre_build"));
            List<String> build = parseCommands(phases.path("build"));
            List<String> postBuild = parseCommands(phases.path("post_build"));

            ParsedArtifacts artifacts = parseArtifacts(root.path("artifacts"));

            return new ParsedBuildspec(envVars, paramStore, secretsMgr,
                    install, preBuild, build, postBuild, artifacts);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidInputException", "Failed to parse buildspec: " + e.getMessage(), 400);
        }
    }

    /**
     * The buildspec without its {@code env.parameter-store} and {@code env.secrets-manager} sections, for
     * a reader that is handed their values as variables and must not look them up itself. Every other line
     * is kept as written, so a value reads as it did: a YAML buildspec loses the sections' lines alone, and
     * only a JSON one is written again.
     */
    static String withoutResolvedVariables(String content) {
        JsonNode parsed = parse(content, "Failed to parse buildspec");
        if (!(parsed.path("env") instanceof ObjectNode env) || RESOLVED_SECTIONS.stream().noneMatch(env::has)) {
            return content;
        }
        if (content.stripLeading().startsWith("{")) {
            RESOLVED_SECTIONS.forEach(env::remove);
            try {
                return JSON.writeValueAsString(parsed);
            } catch (JsonProcessingException e) {
                throw new AwsException("InvalidInputException", "Failed to write buildspec: " + e.getMessage(), 400);
            }
        }
        StringBuilder kept = new StringBuilder();
        boolean inEnv = false;
        int skippedIndent = -1;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            int indent = line.length() - line.stripLeading().length();
            if (skippedIndent >= 0) {
                if (trimmed.isEmpty() || indent > skippedIndent) {
                    continue;
                }
                skippedIndent = -1;
            }
            if (indent == 0 && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                inEnv = trimmed.startsWith("env:");
            }
            if (inEnv && indent > 0 && RESOLVED_SECTIONS.stream().anyMatch(name -> trimmed.startsWith(name + ":"))) {
                skippedIndent = indent;
                continue;
            }
            kept.append(line).append('\n');
        }
        String result = kept.toString();
        JsonNode reparsed = parse(result, "Failed to parse buildspec");
        if (RESOLVED_SECTIONS.stream().anyMatch(name -> reparsed.path("env").has(name))) {
            throw new AwsException("InvalidInputException",
                    "Failed to parse buildspec: env.parameter-store or env.secrets-manager is not a block of its own", 400);
        }
        return result;
    }

    private static JsonNode parse(String content, String failure) {
        try {
            return YAML.readTree(content);
        } catch (Exception e) {
            throw new AwsException("InvalidInputException", failure + ": " + e.getMessage(), 400);
        }
    }

    private static List<String> parseCommands(JsonNode phaseNode) {
        if (phaseNode.isMissingNode() || phaseNode.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode cmd : phaseNode.path("commands")) {
            result.add(cmd.asText());
        }
        return result;
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return result;
        }
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    private static ParsedArtifacts parseArtifacts(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return new ParsedArtifacts("NO_ARTIFACTS", List.of(), null, false, null, "ZIP");
        }
        String type = node.path("type").asText("NO_ARTIFACTS");
        List<String> files = new ArrayList<>();
        for (JsonNode f : node.path("files")) {
            files.add(f.asText());
        }
        String baseDir = node.has("base-directory") ? node.path("base-directory").asText(null) : null;
        boolean discardPaths = node.path("discard-paths").asBoolean(false);
        String name = node.has("name") ? node.path("name").asText(null) : null;
        String packaging = node.path("packaging").asText("ZIP");
        return new ParsedArtifacts(type, files, baseDir, discardPaths, name, packaging);
    }
}
