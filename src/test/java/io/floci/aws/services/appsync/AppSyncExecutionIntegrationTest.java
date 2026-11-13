package io.floci.aws.services.appsync;

import io.floci.aws.services.appsync.graphql.AppSyncErrorFormatter;
import io.floci.aws.services.appsync.graphql.SchemaCreationWorker;
import io.floci.aws.services.appsync.graphql.SchemaRegistry;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the real GraphQL sidecar image, started by {@link GraphqlSidecarManager}. */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncExecutionIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    @Inject
    SchemaRegistry schemaRegistry;

    private String apiId;
    private String apiKey;

    @BeforeAll
    static void configureRestAssured() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createApiWithSchema() {
        apiId = createApi("exec-" + UUID.randomUUID().toString().substring(0, 8));
        apiKey = createApiKey(apiId);
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
    }

    @Test
    void dualContentTypeJsonReturnsNullHello() {
        given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("data.hello", nullValue())
            .body("errors", nullValue());
    }

    @Test
    void dualContentTypeGraphqlReturnsNullHello() {
        given()
            .header("x-api-key", apiKey)
            .contentType("application/graphql")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    @Test
    void missingContentTypeReturns400GraphqlErrors() {
        given()
            .header("Authorization", AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("__type", nullValue());
    }

    @Test
    void unsupportedContentTypeReturns400GraphqlErrors() {
        given()
            .header("Authorization", AUTH)
            .contentType("text/plain")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("__type", nullValue());
    }

    @Test
    void emptyBodyReturns400MalformedWithEmptyMessage() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .header("x-amzn-errortype", containsString("MalformedHttpRequestException"))
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_EMPTY_BODY));
    }

    @Test
    void emptyObjectBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void arrayBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("[{\"query\":\"{ hello }\"}]")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void unparseableBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("meow")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void unknownApiIdReturns404() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/does-not-exist-xyz/graphql")
        .then()
            .statusCode(404)
            .header("x-amzn-errortype", containsString("NotFoundException"));
    }

    @Test
    void apiWithoutSchemaReturns502GraphQLSchemaException() {
        String bareApiId = createApi("bare-" + UUID.randomUUID().toString().substring(0, 8));
        String bareKey = createApiKey(bareApiId);

        given()
            .header("x-api-key", bareKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + bareApiId + "/graphql")
        .then()
            .statusCode(502)
            .header("x-amzn-errortype", containsString("GraphQLSchemaException"))
            .body("errors[0].errorType", equalTo("GraphQLSchemaException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_NO_SCHEMA));
    }

    @Test
    void validationErrorReturns200WithTopLevelErrorType() {
        given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"{ nope }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].extensions", nullValue());
    }

    @Test
    void syntaxErrorReturns200WithSyntaxError() {
        given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"));
    }

    @Test
    void introspectionReturnsNonEmptyTypes() {
        Map<String, Object> body = given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"{ __schema { types { name } } }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .extract().jsonPath().getMap("");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        @SuppressWarnings("unchecked")
        List<?> types = (List<?>) schema.get("types");
        assertThat(types, hasSize(greaterThan(0)));
    }

    @Test
    void variablesAndOperationNameExecuteSelectedOp() {
        String multiApi = createApi("multi-" + UUID.randomUUID().toString().substring(0, 8));
        String multiKey = createApiKey(multiApi);
        startSchema(multiApi, "type Query { hello(id: String): String }");
        awaitSchemaSuccess(multiApi);

        given()
            .header("x-api-key", multiKey)
            .contentType("application/json")
            .body("""
                {
                  "query": "query GetHello($id: String) { hello(id: $id) } query Other { hello }",
                  "variables": {"id": "x"},
                  "operationName": "GetHello"
                }
                """)
        .when()
            .post("/v1/apis/" + multiApi + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    /**
     * AppSync serializes a resolver's answer to an AWSJSON field once more on the way out: a map is served as
     * JSON text that parses to the map, and JSON text answered as a string is served as a string encoding it,
     * one beginning {@code "{}, which a reader parses twice; each element of a list of AWSJSON the same way.
     */
    @Test
    void aResolversAnswerToAnAwsJsonFieldIsServedSerializedOnceMore() {
        String api = createApi("awsjson-" + UUID.randomUUID().toString().substring(0, 8));
        String key = createApiKey(api);
        startSchema(api, "type Query { asMap: AWSJSON, asText: AWSJSON, asList: [AWSJSON] }");
        awaitSchemaSuccess(api);
        noneDataSource(api);
        noneResolver(api, "asMap", "$util.toJson({\"a\": 1})");
        noneResolver(api, "asText", "$util.toJson($util.toJson({\"a\": 1}))");
        noneResolver(api, "asList", "$util.toJson([{\"a\": 1}, $util.toJson({\"b\": 2})])");

        given()
            .header("x-api-key", key)
            .contentType("application/json")
            .body("{\"query\":\"{ asMap asText asList }\"}")
        .when()
            .post("/v1/apis/" + api + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.asMap", equalTo("{\"a\":1}"))
            .body("data.asText", equalTo("\"{\\\"a\\\":1}\""))
            .body("data.asList[0]", equalTo("{\"a\":1}"))
            .body("data.asList[1]", equalTo("\"{\\\"b\\\":2}\""));
    }

    /**
     * AppSync takes an AWSJSON variable as a string of JSON text alone and refuses a JSON object before any
     * resolver runs; the text is parsed into maps and lists before the resolver sees it, so a resolver reads
     * the argument's fields, and one echoing it answers the map, served as text.
     */
    @Test
    void anAwsJsonVariableIsAStringParsedBeforeTheResolverRuns() {
        String api = createApi("awsjson-var-" + UUID.randomUUID().toString().substring(0, 8));
        String key = createApiKey(api);
        startSchema(api, "type Query { echo(v: AWSJSON): AWSJSON, inner(v: AWSJSON): Int }");
        awaitSchemaSuccess(api);
        noneDataSource(api);
        noneResolver(api, "echo", "$util.toJson($ctx.args.v)");
        noneResolver(api, "inner", "$util.toJson($ctx.args.v.a)");

        given()
            .header("x-api-key", key)
            .contentType("application/json")
            .body("{\"query\":\"query Q($v: AWSJSON) { echo(v: $v) }\",\"variables\":{\"v\":{\"a\":1}}}")
        .when()
            .post("/v1/apis/" + api + "/graphql")
        .then()
            .statusCode(200)
            .body("data", nullValue())
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].message", containsString("Variable 'v' has an invalid value"));

        given()
            .header("x-api-key", key)
            .contentType("application/json")
            .body("{\"query\":\"query Q($v: AWSJSON) { echo(v: $v) inner(v: $v) }\","
                + "\"variables\":{\"v\":\"{\\\"a\\\":1}\"}}")
        .when()
            .post("/v1/apis/" + api + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.echo", equalTo("{\"a\":1}"))
            .body("data.inner", equalTo(1));
    }

    private static void noneDataSource(String api) {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"name\": \"none\", \"type\": \"NONE\"}")
            .when().post("/v1/apis/" + api + "/datasources").then().statusCode(200);
    }

    /** A VTL unit resolver on the NONE data source whose answer is {@code responseTemplate}'s. */
    private static void noneResolver(String api, String field, String responseTemplate) {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"dataSourceName\": \"none\", \"fieldName\": \"" + field + "\", \"typeName\": \"Query\","
                + " \"requestMappingTemplate\": \"{\\\"version\\\":\\\"2018-05-29\\\",\\\"payload\\\":{}}\","
                + " \"responseMappingTemplate\": " + quote(responseTemplate) + "}")
            .when().post("/v1/apis/" + api + "/types/Query/resolvers").then().statusCode(200);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void httpSubscriptionReturns200OperationNotSupported() {
        String subApi = createApi("sub-" + UUID.randomUUID().toString().substring(0, 8));
        String subKey = createApiKey(subApi);
        startSchema(subApi, """
                type Query { hello: String }
                type Subscription { onHello: String }
                """);
        awaitSchemaSuccess(subApi);

        given()
            .header("x-api-key", subKey)
            .contentType("application/json")
            .body("{\"query\":\"subscription { onHello }\"}")
        .when()
            .post("/v1/apis/" + subApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("OperationNotSupported"));
    }

    @Test
    void awsDateTimeLiteralCoercionRejectsInvalidValueAsValidationErrorNotServerError() {
        // graphql-java only catches CoercingParseLiteralException while validating a literal
        // argument, and every AppSyncScalars parseLiteral() used to delegate to parseValue(),
        // which throws CoercingParseValueException instead: uncaught, that escaped as an HTTP
        // 500 rather than becoming a spec-correct ValidationError. Fixed in AppSyncScalars.
        String scalarApi = createApi("scalar-lit-" + UUID.randomUUID().toString().substring(0, 8));
        String scalarKey = createApiKey(scalarApi);
        startSchema(scalarApi, "type Query { echo(t: AWSDateTime): AWSDateTime }");
        awaitSchemaSuccess(scalarApi);

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("{\"query\":\"{ echo(t: \\\"not-a-date\\\") }\"}")
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].message", containsString("Invalid AWSDateTime"));

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("{\"query\":\"{ echo(t: \\\"2026-01-01T00:00:00Z\\\") }\"}")
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue());
    }

    @Test
    void awsDateTimeVariableCoercionRejectsInvalidValueAndAcceptsValid() {
        // Proves the real AWSDateTime Coercing actually runs through the sidecar's schema
        // build, not just that StartSchemaCreation accepts the type name (issue #2917).
        String scalarApi = createApi("scalar-" + UUID.randomUUID().toString().substring(0, 8));
        String scalarKey = createApiKey(scalarApi);
        startSchema(scalarApi, "type Query { echo(t: AWSDateTime): AWSDateTime }");
        awaitSchemaSuccess(scalarApi);

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("""
                {
                  "query": "query($t: AWSDateTime) { echo(t: $t) }",
                  "variables": {"t": "not-a-date"}
                }
                """)
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].message", containsString("Invalid AWSDateTime"));

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("""
                {
                  "query": "query($t: AWSDateTime) { echo(t: $t) }",
                  "variables": {"t": "2026-01-01T00:00:00Z"}
                }
                """)
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue());
    }

    @Test
    void fragmentCycleAgainstProtectedFieldNeverLeaksRealData() {
        // Proves the sidecar's fail-closed /v1/plan behavior: a query the planner can't
        // analyze must never fall back to "nothing to authorize, run it anyway."
        String protectedApi = createApi("protected-" + UUID.randomUUID().toString().substring(0, 8));
        String protectedKey = createApiKey(protectedApi);
        startSchema(protectedApi, "type Query { hello: String secret: String @aws_iam }");
        awaitSchemaSuccess(protectedApi);

        given()
            .header("x-api-key", protectedKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello secret ...a } fragment a on Query { ...a }\"}")
        .when()
            .post("/v1/apis/" + protectedApi + "/graphql")
        .then()
            .body("data.secret", nullValue());
    }

    @Test
    void whitespaceOnlyQueryReturns200SyntaxError() {
        given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"   \"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"));
    }

    @Test
    void rehydrateFromSchemaStoreEnablesExecute() {
        String hydrateApi = createApi("hydrate-" + UUID.randomUUID().toString().substring(0, 8));
        String hydrateKey = createApiKey(hydrateApi);
        startSchema(hydrateApi, "type Query { hello: String }");
        awaitSchemaSuccess(hydrateApi);

        schemaRegistry.remove(hydrateApi);
        assertTrue(schemaRegistry.getSdl(hydrateApi).isEmpty());

        given()
            .header("x-api-key", hydrateKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + hydrateApi + "/graphql")
        .then()
            .statusCode(502);

        // invoke rehydrate via worker (package-visible through CDI bean)
        rehydrateWorker().rehydrateSchemas();

        given()
            .header("x-api-key", hydrateKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + hydrateApi + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    @Inject
    SchemaCreationWorker schemaCreationWorker;

    private SchemaCreationWorker rehydrateWorker() {
        return schemaCreationWorker;
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "%s", "authenticationType": "API_KEY"}
                """.formatted(name))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");
    }

    private static String createApiKey(String apiId) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.id");
    }

    private static void startSchema(String apiId, String definition) {
        String escaped = definition.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escaped + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("PROCESSING"));
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    String status = given()
                        .header("Authorization", AUTH)
                    .when()
                        .get("/v1/apis/" + apiId + "/schemacreation")
                    .then()
                        .statusCode(200)
                        .extract().path("status");
                    return "SUCCESS".equals(status);
                });
    }
}
