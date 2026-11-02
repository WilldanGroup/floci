package io.github.hectorvent.floci.services.appsync.graphql.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AuthRequestInfo(
        String query,
        String operationName,
        Map<String, Object> variables,
        List<String> sourceIp,
        String requestId,
        String accountId,
        String region,
        Map<String, String> requestHeaders,
        String rawBody
) {
    public AuthRequestInfo {
        sourceIp = sourceIp == null ? List.of() : List.copyOf(sourceIp);
        // Null-tolerant, as ResolverInvocation copies them: a nullable variable given as null is
        // kept as null (GraphQL, October 2021, 6.1.2 Coercing Variable Values), and Map.copyOf
        // refuses a null value, which failed the whole request with InternalFailure.
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        rawBody = rawBody == null ? "" : rawBody;
    }
}
