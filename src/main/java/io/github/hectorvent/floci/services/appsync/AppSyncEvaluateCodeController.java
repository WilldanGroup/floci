package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.js.AppSyncJsRuntime;
import io.github.hectorvent.floci.services.appsync.graphql.js.JsEvaluation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EvaluateCode: runs one handler of {@code APPSYNC_JS} code against a context, in the runtime resolvers run in,
 * and answers what it returned, creating nothing (AWS AppSync API Reference, EvaluateCode). The result, the stash
 * and the errors the code appended come back as JSON text; a handler that throws, or calls {@code util.error}, comes
 * back as the answer's {@code error}. Floci keeps no {@code util.log} lines, so {@code logs} is empty.
 */
@Path("/v1/dataplane-evaluatecode")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppSyncEvaluateCodeController {

    private static final TypeReference<Map<String, Object>> CONTEXT = new TypeReference<>() { };

    private final AppSyncJsRuntime jsRuntime;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncEvaluateCodeController(AppSyncJsRuntime jsRuntime, ObjectMapper objectMapper) {
        this.jsRuntime = jsRuntime;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response evaluateCode(String body) throws JsonProcessingException {
        JsonNode request = readJson(body, "The request body is not JSON");
        String code = request.path("code").asText("");
        String context = request.path("context").asText("");
        String runtime = request.path("runtime").path("name").asText("");
        String function = request.path("function").asText("request");
        if (code.isEmpty()) {
            throw badRequest("code is required");
        }
        if (context.length() < 2) {
            throw badRequest("context is required");
        }
        if (!"APPSYNC_JS".equals(runtime)) {
            throw badRequest("The runtime must be APPSYNC_JS");
        }
        if (!"request".equals(function) && !"response".equals(function)) {
            throw badRequest("function must be request or response");
        }
        Map<String, Object> ctx;
        try {
            ctx = new LinkedHashMap<>(objectMapper.readValue(context, CONTEXT));
        } catch (JsonProcessingException e) {
            throw badRequest("context is not a JSON object");
        }
        if (ctx.containsKey("arguments") && !ctx.containsKey("args")) {
            ctx.put("args", ctx.get("arguments"));
        }

        JsEvaluation evaluation = jsRuntime.evaluate(code, function, ctx);
        ObjectNode answer = objectMapper.createObjectNode();
        if (evaluation.failed()) {
            answer.putObject("error").put("message", evaluation.error().message());
        } else if (evaluation.missingHandler()) {
            answer.putObject("error").put("message", "The code exports no " + function + " function");
        } else {
            answer.put("evaluationResult", objectMapper.writeValueAsString(evaluation.result()));
        }
        answer.put("stash", objectMapper.writeValueAsString(
                evaluation.stash() != null ? evaluation.stash() : Map.of()));
        answer.put("outErrors", objectMapper.writeValueAsString(outErrors(evaluation.appendedErrors())));
        answer.putArray("logs");
        return Response.ok(answer).build();
    }

    /** The errors {@code util.appendError} added, as the GraphQL errors AppSync reports them. */
    private static List<Map<String, Object>> outErrors(List<JsEvaluation.JsError> appended) {
        if (appended == null) {
            return List.of();
        }
        return appended.stream().map(error -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", error.message());
            out.put("errorType", error.type());
            out.put("data", error.data());
            out.put("errorInfo", error.errorInfo());
            return out;
        }).toList();
    }

    private JsonNode readJson(String body, String refusal) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw badRequest(refusal);
        }
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
