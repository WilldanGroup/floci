package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code APPSYNC_JS} resolvers over an {@code AMAZON_DYNAMODB} data source calling the batch and
 * transaction operations, end to end against Floci's own DynamoDB: a GraphQL request, the sidecar,
 * the resolver, the native tables and back. The request and result shapes are AppSync's JavaScript
 * resolver function reference for DynamoDB, BatchGetItem and TransactWriteItems.
 *
 * <p>Needs Docker for the GraphQL sidecar, and skips without it.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncDynamoDbBatchTransactionIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    private static final String SCHEMA = """
            type Query { readBack: String }
            type Mutation { writeBoth(title: String!): String }
            """;

    /** Two items in two tables, the post's put conditioned on no post standing. */
    private static final String WRITE_BOTH = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              return { operation: "TransactWriteItems", transactItems: [
                { table: "%1$s", operation: "PutItem", key: util.dynamodb.toMapValues({ id: "p1" }),
                  attributeValues: util.dynamodb.toMapValues({ title: ctx.args.title }),
                  condition: { expression: "attribute_not_exists(id)" } },
                { table: "%2$s", operation: "PutItem", key: util.dynamodb.toMapValues({ id: "a1" }),
                  attributeValues: util.dynamodb.toMapValues({ name: "Ana" }) }
              ] };
            }
            export function response(ctx) {
              if (ctx.error) {
                return JSON.stringify({ errorType: ctx.error.type, result: ctx.result });
              }
              return JSON.stringify(ctx.result);
            }
            """;

    /** Both tables in one batch, a key with no item between two that have one. */
    private static final String READ_BACK = """
            import { util } from "@aws-appsync/utils";
            export function request(ctx) {
              const tables = {};
              tables["%1$s"] = { consistentRead: true, keys: [
                util.dynamodb.toMapValues({ id: "p1" }), util.dynamodb.toMapValues({ id: "none" }) ] };
              tables["%2$s"] = { keys: [ util.dynamodb.toMapValues({ id: "a1" }) ] };
              return { operation: "BatchGetItem", tables };
            }
            export function response(ctx) {
              return JSON.stringify(ctx.result);
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private String apiId;
    private String apiKey;
    private String posts;
    private String authors;

    @BeforeAll
    static void configure() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void deployApi() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        posts = createTable("posts-" + suffix);
        authors = createTable("authors-" + suffix);
        apiId = createApi("ddb-" + suffix);
        apiKey = createApiKey(apiId);
        startSchema(apiId, SCHEMA);
        awaitSchemaSuccess(apiId);
        createDynamoDbDataSource(apiId, "posts", posts);
        createUnitResolver(apiId, "Mutation", "writeBoth", "posts", WRITE_BOTH.formatted(posts, authors));
        createUnitResolver(apiId, "Query", "readBack", "posts", READ_BACK.formatted(posts, authors));
    }

    @Test
    void aTransactionWritesEachItemToItsTableAndABatchReadsThemBackInKeyOrder() throws Exception {
        JsonNode written = field("mutation { writeBoth(title: \\\"first\\\") }", "writeBoth");
        assertEquals(mapper.readTree("""
                {"keys": [{"id": "p1"}, {"id": "a1"}], "cancellationReasons": null}
                """), written);

        JsonNode read = field("{ readBack }", "readBack");
        assertEquals(mapper.readTree("""
                {"data": {"%s": [{"id": "p1", "title": "first"}, null],
                          "%s": [{"id": "a1", "name": "Ana"}]},
                 "unprocessedKeys": {"%s": [], "%s": []}}
                """.formatted(posts, authors, posts, authors)), read);
    }

    @Test
    void aLostConditionCancelsTheTransactionWithTheStandingItemBesideTheError() throws Exception {
        field("mutation { writeBoth(title: \\\"first\\\") }", "writeBoth");

        JsonNode lost = field("mutation { writeBoth(title: \\\"second\\\") }", "writeBoth");

        assertEquals("DynamoDB:TransactionCanceledException", lost.path("errorType").asText());
        JsonNode result = lost.path("result");
        assertTrue(result.has("keys") && result.path("keys").isNull(), result.toString());
        JsonNode reasons = result.path("cancellationReasons");
        assertEquals(2, reasons.size(), reasons.toString());
        assertEquals("ConditionalCheckFailed", reasons.get(0).path("type").asText());
        // The post that stood, as the condition found it: AppSync asks for it unless told not to.
        assertEquals(mapper.readTree("{\"id\": \"p1\", \"title\": \"first\"}"), reasons.get(0).path("item"));
        assertEquals(mapper.readTree("{\"type\": \"None\", \"message\": \"None\"}"), reasons.get(1));
        // Nothing of the cancelled transaction was written.
        assertEquals("first", field("{ readBack }", "readBack")
                .path("data").path(posts).get(0).path("title").asText());
    }

    private JsonNode field(String graphql, String name) throws Exception {
        String value = given()
            .header("x-api-key", apiKey)
            .contentType("application/json")
            .body("{\"query\":\"" + graphql + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue())
            .extract().path("data." + name);
        return mapper.readTree(value);
    }

    private static String createTable(String name) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType("application/x-amz-json-1.0")
            .body("""
                {"TableName": "%s",
                 "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                 "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                 "BillingMode": "PAY_PER_REQUEST"}
                """.formatted(name).getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        return name;
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"name\": \"%s\", \"authenticationType\": \"API_KEY\"}".formatted(name))
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
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escape(definition) + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200);
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                "SUCCESS".equals(given().header("Authorization", AUTH)
                    .when().get("/v1/apis/" + apiId + "/schemacreation")
                    .then().statusCode(200).extract().path("status")));
    }

    private static void createDynamoDbDataSource(String apiId, String name, String table) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "%s", "type": "AMAZON_DYNAMODB",
                 "dynamodbConfig": {"tableName": "%s", "awsRegion": "us-east-1"}}
                """.formatted(name, table))
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200);
    }

    private static void createUnitResolver(String apiId, String typeName, String fieldName,
                                           String dataSourceName, String code) {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"typeName": "%s", "fieldName": "%s", "kind": "UNIT", "dataSourceName": "%s",
                 "runtime": {"name": "APPSYNC_JS", "runtimeVersion": "1.0.0"},
                 "code": "%s"}
                """.formatted(typeName, fieldName, dataSourceName, escape(code)))
        .when()
            .post("/v1/apis/" + apiId + "/types/" + typeName + "/resolvers")
        .then()
            .statusCode(200);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
