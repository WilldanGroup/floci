package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.SdlFieldTypes;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolver callback the GraphQL sidecar calls back into, one HTTP call per execution level.
 *
 * <p>The sidecar runs the query and owns the GraphQL engine; Floci owns the resolvers and the data
 * sources. This is the seam between them: the sidecar sends every field of one execution level it
 * cannot resolve itself, Floci runs each field's resolver, and the answers become those fields'
 * values and the {@code source} of their own children. The contract is {@code graphql/API.md} in
 * floci-io/floci-sidecars.
 *
 * <p>A fixed Floci-internal route, presented to the container as an absolute URL and authorised by
 * a per-execution bearer token rather than by AWS credentials. It sits under the {@code _floci/}
 * namespace, with the EKS webhooks, rather than at a bare path: a bare path is reachable as a
 * path-style S3 request for a bucket of that name, and an S3 bucket name cannot begin with an
 * underscore, so this prefix cannot be shadowed by one.
 */
@Path("/_floci/appsync/resolve")
public class ResolverCallbackResource {

    private static final Logger LOG = Logger.getLogger(ResolverCallbackResource.class);
    private static final String BEARER = "Bearer ";

    private final ResolverCallbackSessions sessions;
    private final AppSyncResolverExecutor executor;
    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    @Inject
    public ResolverCallbackResource(ResolverCallbackSessions sessions,
                                    AppSyncResolverExecutor executor,
                                    SchemaRegistry schemaRegistry,
                                    ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.executor = executor;
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resolve(@HeaderParam("Authorization") String authorization, String body) {
        Optional<ResolverCallbackSessions.Session> session = sessions.find(bearerToken(authorization));
        if (session.isEmpty()) {
            // The sidecar turns any non-200 into a field error on exactly this batch, so an expired
            // or forged token fails those fields rather than the whole operation.
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unknown or expired resolver callback token")).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Malformed resolver callback body")).build();
        }
        JsonNode invocations = root == null ? null : root.get("invocations");
        if (invocations == null || !invocations.isArray()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Resolver callback body is missing invocations")).build();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : invocations) {
            results.add(answer(session.get(), node));
        }
        return Response.ok(Map.of("results", results)).build();
    }

    private Map<String, Object> answer(ResolverCallbackSessions.Session session, JsonNode node) {
        String id = node.path("id").asText();
        ResolverInvocation invocation = invocationFrom(session, node);
        Resolver resolver;
        try {
            resolver = executor.findResolver(invocation.apiId(), invocation.typeName(), invocation.fieldName());
        } catch (AwsException e) {
            return error(id, new AppSyncResolverError(e.getMessage(), e.getErrorCode(), null, null, null));
        }
        if (resolver == null) {
            // The coordinate had a resolver when the operation was planned and does not now, which a
            // deploy mid-operation can do. Falling back to the source property is what a GraphQL
            // server does for a field with no resolver, so the field reads as it would have.
            return success(id, sourceProperty(invocation));
        }
        ResolverOutcome outcome = executor.execute(resolver, invocation);
        session.appendErrors(outcome.appendedErrors());
        return outcome.failed() ? error(id, outcome.error()) : success(id, asServed(invocation, outcome.data()));
    }

    /**
     * The resolver's answer as AppSync serves it. AppSync serializes a resolver's answer to an AWSJSON field
     * once more on the way out: a map is served as the JSON text of the map, and JSON text answered as a string
     * is served as a string encoding that text, one beginning {@code "{}, which a reader parses twice. The
     * sidecar's json-string coercion serializes a map or a list to text the same way but passes a string
     * through as it stands, so a string answered to an AWSJSON field, or each string in a list answered to a
     * list of AWSJSON, is encoded here before the sidecar sees it. Every other value, and a field of any
     * other type, is left to the sidecar.
     */
    private Object asServed(ResolverInvocation invocation, Object data) {
        if (!(data instanceof String) && !(data instanceof List<?>)) {
            return data;
        }
        Optional<SdlFieldTypes.FieldType> declared = schemaRegistry.getSdl(invocation.apiId())
                .flatMap(sdl -> SdlFieldTypes.declaredType(sdl, invocation.typeName(), invocation.fieldName()));
        if (declared.isEmpty() || !"AWSJSON".equals(declared.get().name())) {
            return data;
        }
        if (data instanceof String text && !declared.get().list()) {
            return encoded(text);
        }
        if (data instanceof List<?> list && declared.get().list()) {
            List<Object> served = new ArrayList<>(list.size());
            for (Object element : list) {
                served.add(element instanceof String text ? encoded(text) : element);
            }
            return served;
        }
        return data;
    }

    /** The JSON encoding of a string, quotes included, as AppSync serializes a string answered to AWSJSON. */
    private String encoded(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A string could not be encoded as JSON", e);
        }
    }

    private ResolverInvocation invocationFrom(ResolverCallbackSessions.Session session, JsonNode node) {
        String typeName = node.path("typeName").asText();
        String fieldName = node.path("fieldName").asText();
        return new ResolverInvocation(
                session.apiId(),
                typeName,
                fieldName,
                asAppSyncHandsThem(session.apiId(), typeName, fieldName, asMap(node.get("arguments"))),
                asPlain(node.get("source")),
                asList(node.get("path")),
                asMap(node.get("variables")),
                asStrings(node.get("selectionSetList")),
                session.identity(),
                session.authType());
    }

    /**
     * The arguments as AppSync hands them to a resolver: an AWSJSON argument is JSON text on the wire, which
     * the sidecar's json-string coercion keeps as text, while AppSync parses it into maps and lists before the
     * resolver reads {@code ctx.args}. So each argument declared AWSJSON, each element of a list of them, and
     * each such field of an input object argument, is parsed here; text that is not JSON is left as it came.
     */
    private Map<String, Object> asAppSyncHandsThem(String apiId, String typeName, String fieldName,
                                                   Map<String, Object> arguments) {
        Optional<String> sdl = schemaRegistry.getSdl(apiId);
        if (sdl.isEmpty() || arguments.isEmpty()) {
            return arguments;
        }
        Map<String, Object> handed = new LinkedHashMap<>();
        arguments.forEach((name, value) -> handed.put(name,
                parsed(sdl.get(), SdlFieldTypes.argumentType(sdl.get(), typeName, fieldName, name).orElse(null), value)));
        return handed;
    }

    private Object parsed(String sdl, SdlFieldTypes.FieldType declared, Object value) {
        if (declared == null || value == null) {
            return value;
        }
        if (declared.list()) {
            if (!(value instanceof List<?> list)) {
                return value;
            }
            SdlFieldTypes.FieldType element = new SdlFieldTypes.FieldType(declared.name(), false);
            List<Object> elements = new ArrayList<>(list.size());
            list.forEach(item -> elements.add(parsed(sdl, element, item)));
            return elements;
        }
        if ("AWSJSON".equals(declared.name())) {
            if (!(value instanceof String text)) {
                return value;
            }
            try {
                return objectMapper.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                return value;
            }
        }
        if (value instanceof Map<?, ?> fields && SdlFieldTypes.isInputType(sdl, declared.name())) {
            Map<String, Object> input = new LinkedHashMap<>();
            fields.forEach((key, field) -> input.put(String.valueOf(key),
                    parsed(sdl, SdlFieldTypes.declaredType(sdl, declared.name(), String.valueOf(key)).orElse(null),
                            field)));
            return input;
        }
        return value;
    }

    /** What graphql-java's own property fetcher would have read off the parent value. */
    private static Object sourceProperty(ResolverInvocation invocation) {
        return invocation.source() instanceof Map<?, ?> source ? source.get(invocation.fieldName()) : null;
    }

    private static Map<String, Object> success(String id, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        // Deliberately a HashMap entry rather than Map.of: a resolver returning null is a valid
        // value, and Map.of rejects it.
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> error(String id, AppSyncResolverError raised) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", raised.message());
        error.put("type", raised.errorType());
        error.put("data", raised.data());
        error.put("info", raised.errorInfo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("error", error);
        return result;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return authorization.substring(BEARER.length()).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        Object value = asPlain(node);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(JsonNode node) {
        Object value = asPlain(node);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private List<String> asStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> values.add(entry.asText()));
        }
        return values;
    }

    private Object asPlain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, Object.class);
        } catch (IllegalArgumentException e) {
            LOG.debugv(e, "Unreadable value in a resolver callback invocation");
            return null;
        }
    }
}
