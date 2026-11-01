package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AppSync-to-DynamoDB translation, in both directions. The direction that matters is outward:
 * AppSync hands a resolver plain JSON, never attribute values.
 */
class DynamoDbDataSourceInvokerTest {

    private final DynamoDbJsonHandler handler = mock(DynamoDbJsonHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbDataSourceInvoker invoker = new DynamoDbDataSourceInvoker(handler, mapper);

    private DataSource dataSource() {
        DataSource ds = new DataSource();
        ds.setName("authKeys");
        ds.setType(DataSourceType.AMAZON_DYNAMODB);
        ds.setDynamodbConfig(Map.of("tableName", "auth-keys", "awsRegion", "eu-west-1"));
        return ds;
    }

    private void answers(String json) throws Exception {
        when(handler.handle(anyString(), any(), anyString())).thenReturn(Response.ok(json).build());
    }

    private JsonNode captureRequest(String action) throws Exception {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(handler).handle(eq(action), captor.capture(), anyString());
        return captor.getValue();
    }

    private Map<String, Object> request(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void getItemUnmarshalsTheItemIntoPlainJson() throws Exception {
        answers("""
            {"Item": {"regNo": {"S": "556677"}, "calls": {"N": "42"}, "active": {"BOOL": true},
                      "scopes": {"L": [{"S": "read"}, {"S": "write"}]},
                      "meta": {"M": {"tier": {"S": "small"}}}, "gone": {"NULL": true}}}
            """);

        Object result = invoker.invoke(dataSource(),
                request("{\"version\": \"2018-05-29\", \"operation\": \"GetItem\", "
                        + "\"key\": {\"regNo\": {\"S\": \"556677\"}}}"),
                "eu-west-1");

        // A GraphQL field cannot be built from {"S": …}; AppSync resolvers see plain values.
        assertEquals(Map.of("regNo", "556677", "calls", 42L, "active", true,
                        "scopes", List.of("read", "write"), "meta", Map.of("tier", "small")),
                stripNulls(result));
        JsonNode sent = captureRequest("GetItem");
        assertEquals("auth-keys", sent.path("TableName").asText());
        assertEquals("556677", sent.path("Key").path("regNo").path("S").asText());
    }

    @Test
    void getItemMissAnswersNullRatherThanAnEmptyMap() throws Exception {
        answers("{}");

        Object result = invoker.invoke(dataSource(),
                request("{\"operation\": \"GetItem\", \"key\": {\"regNo\": {\"S\": \"nope\"}}}"),
                "eu-west-1");

        assertNull(result);
    }

    @Test
    void putItemMergesKeyAndAttributeValuesAndAnswersTheWrittenItem() throws Exception {
        answers("{}");

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "PutItem",
                 "key": {"regNo": {"S": "556677"}},
                 "attributeValues": {"name": {"S": "Acme"}},
                 "condition": {"expression": "attribute_not_exists(#r)",
                               "expressionNames": {"#r": "regNo"}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("PutItem");
        // The key is part of the item on the way to DynamoDB, and wins over an attribute of the
        // same name, as AppSync documents.
        assertEquals("556677", sent.path("Item").path("regNo").path("S").asText());
        assertEquals("Acme", sent.path("Item").path("name").path("S").asText());
        assertEquals("attribute_not_exists(#r)", sent.path("ConditionExpression").asText());
        assertEquals("regNo", sent.path("ExpressionAttributeNames").path("#r").asText());
        assertEquals(Map.of("regNo", "556677", "name", "Acme"), result);
    }

    @Test
    void updateItemAsksForTheNewImageAndUnmarshalsIt() throws Exception {
        answers("{\"Attributes\": {\"regNo\": {\"S\": \"556677\"}, \"calls\": {\"N\": \"43\"}}}");

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "UpdateItem",
                 "key": {"regNo": {"S": "556677"}},
                 "update": {"expression": "ADD calls :one",
                            "expressionValues": {":one": {"N": "1"}}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("UpdateItem");
        assertEquals("ADD calls :one", sent.path("UpdateExpression").asText());
        assertEquals("1", sent.path("ExpressionAttributeValues").path(":one").path("N").asText());
        // ALL_NEW: an AppSync UpdateItem resolver returns the updated item.
        assertEquals("ALL_NEW", sent.path("ReturnValues").asText());
        assertEquals(Map.of("regNo", "556677", "calls", 43L), result);
    }

    @Test
    void queryMergesKeyConditionAndFilterPlaceholdersIntoOneMap() throws Exception {
        answers("""
            {"Items": [{"regNo": {"S": "1"}}, {"regNo": {"S": "2"}}],
             "ScannedCount": 7,
             "LastEvaluatedKey": {"regNo": {"S": "2"}}}
            """);

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "Query",
                 "query": {"expression": "#r = :r", "expressionNames": {"#r": "regNo"},
                           "expressionValues": {":r": {"S": "1"}}},
                 "filter": {"expression": "active = :a", "expressionValues": {":a": {"BOOL": true}}},
                 "index": "byRegNo", "limit": 25, "scanIndexForward": false}
                """), "eu-west-1");

        JsonNode sent = captureRequest("Query");
        assertEquals("#r = :r", sent.path("KeyConditionExpression").asText());
        assertEquals("active = :a", sent.path("FilterExpression").asText());
        // Both the key condition's and the filter's placeholders live in one shared map; setting
        // rather than merging them would drop whichever came first.
        assertEquals("1", sent.path("ExpressionAttributeValues").path(":r").path("S").asText());
        assertTrue(sent.path("ExpressionAttributeValues").path(":a").path("BOOL").asBoolean());
        assertEquals("byRegNo", sent.path("IndexName").asText());
        assertEquals(25, sent.path("Limit").asInt());
        assertTrue(sent.has("ScanIndexForward"));

        Map<?, ?> answered = (Map<?, ?>) result;
        assertEquals(List.of(Map.of("regNo", "1"), Map.of("regNo", "2")), answered.get("items"));
        assertEquals(7, answered.get("scannedCount"));
        assertTrue(answered.get("nextToken") instanceof String, "a page with more rows carries a nextToken");
    }

    @Test
    void aQueryPageWithNoMoreRowsCarriesNoNextToken() throws Exception {
        answers("{\"Items\": [], \"ScannedCount\": 0}");

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "Query", "query": {"expression": "regNo = :r",
                 "expressionValues": {":r": {"S": "1"}}}}
                """), "eu-west-1");

        assertNull(result.get("nextToken"));
        assertEquals(List.of(), result.get("items"));
    }

    @Test
    void aNextTokenRoundTripsBackIntoExclusiveStartKey() throws Exception {
        answers("{\"Items\": [], \"LastEvaluatedKey\": {\"regNo\": {\"S\": \"2\"}}}");
        Map<?, ?> first = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "Scan"}
                """), "eu-west-1");
        String nextToken = (String) first.get("nextToken");

        Mockito.reset(handler);
        answers("{\"Items\": []}");
        invoker.invoke(dataSource(), request("""
                {"operation": "Scan", "nextToken": "%s"}
                """.formatted(nextToken)), "eu-west-1");

        JsonNode sent = captureRequest("Scan");
        assertEquals("2", sent.path("ExclusiveStartKey").path("regNo").path("S").asText());
    }

    @Test
    void aTokenFlociDidNotIssueIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"Scan\", \"nextToken\": \"not-a-token!!\"}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("nextToken"), e.getMessage());
    }

    @Test
    void anUnimplementedOperationSaysSoByName() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"Sync\"}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("Sync"), e.getMessage());
    }

    // ── Errors as AppSync names them ─────────────────────────────────────────

    @Test
    void anErrorDynamoDbThrowsIsNamedWithTheDataSourcesPrefix() throws Exception {
        when(handler.handle(anyString(), any(), anyString())).thenThrow(
                new AwsException("ConditionalCheckFailedException", "The conditional request failed", 400));

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(), request("""
                {"operation": "PutItem", "key": {"regNo": {"S": "1"}},
                 "condition": {"expression": "attribute_not_exists(regNo)"}}
                """), "eu-west-1"));

        // AppSync's condition expressions reference: "errorType": "DynamoDB:ConditionalCheckFailedException".
        assertEquals("DynamoDB:ConditionalCheckFailedException", e.getErrorCode());
        assertEquals("The conditional request failed", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void anErrorDynamoDbAnswersInItsBodyIsAnErrorAndNotAResult() throws Exception {
        when(handler.handle(anyString(), any(), anyString())).thenReturn(Response.status(400).entity("""
                {"__type": "com.amazonaws.dynamodb.v20120810#ResourceNotFoundException",
                 "message": "Requested resource not found"}
                """).build());

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"GetItem\", \"key\": {\"regNo\": {\"S\": \"1\"}}}"),
                "eu-west-1"));

        assertEquals("DynamoDB:ResourceNotFoundException", e.getErrorCode());
        assertEquals("Requested resource not found", e.getMessage());
    }

    // ── Batches ──────────────────────────────────────────────────────────────

    @Test
    void batchGetItemAnswersEachTablesItemsInTheOrderItsKeysWereAsked() throws Exception {
        // DynamoDB answers a batch's items in no order; AppSync answers them in the keys' order.
        answers("""
            {"Responses": {
               "posts": [{"id": {"S": "p2"}, "title": {"S": "two"}}, {"id": {"S": "p1"}, "title": {"S": "one"}}],
               "authors": []},
             "UnprocessedKeys": {}}
            """);

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "BatchGetItem", "tables": {
                   "posts": {"keys": [{"id": {"S": "p1"}}, {"id": {"S": "gone"}}, {"id": {"S": "p2"}}],
                             "consistentRead": true},
                   "authors": {"keys": [{"id": {"S": "a1"}}]}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("BatchGetItem");
        assertEquals(3, sent.path("RequestItems").path("posts").path("Keys").size());
        assertTrue(sent.path("RequestItems").path("posts").path("ConsistentRead").asBoolean());
        assertTrue(sent.path("RequestItems").path("authors").path("ConsistentRead").isMissingNode());
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(Arrays.asList(Map.of("id", "p1", "title", "one"), null,
                Map.of("id", "p2", "title", "two")), data.get("posts"));
        assertEquals(Collections.singletonList(null), data.get("authors"));
        assertEquals(Map.of("posts", List.of(), "authors", List.of()), result.get("unprocessedKeys"));
    }

    @Test
    void batchGetItemPlacesAnItemWhoseProjectionLeavesItsKeyOut() throws Exception {
        answers("""
            {"Responses": {"posts": [{"id": {"S": "p1"}, "title": {"S": "one"}}]}}
            """);

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "BatchGetItem", "tables": {"posts": {
                   "keys": [{"id": {"S": "p1"}}],
                   "projection": {"expression": "#t", "expressionNames": {"#t": "title"}}}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("BatchGetItem").path("RequestItems").path("posts");
        assertEquals("#t, #flociKey0", sent.path("ProjectionExpression").asText());
        assertEquals("id", sent.path("ExpressionAttributeNames").path("#flociKey0").asText());
        // The key was asked for only to place the item, so the projection's answer stands.
        assertEquals(Map.of("posts", List.of(Map.of("title", "one"))), result.get("data"));
    }

    @Test
    void batchGetItemAnswersAnUnprocessedKeyAsNullAndListsIt() throws Exception {
        answers("""
            {"Responses": {"posts": []},
             "UnprocessedKeys": {"posts": {"Keys": [{"id": {"S": "p1"}}]}}}
            """);

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "BatchGetItem", "tables": {"posts": {"keys": [{"id": {"S": "p1"}}]}}}
                """), "eu-west-1");

        assertEquals(Map.of("posts", Collections.singletonList(null)), result.get("data"));
        assertEquals(Map.of("posts", List.of(Map.of("id", "p1"))), result.get("unprocessedKeys"));
    }

    @Test
    void batchPutItemWritesEachTablesItemsAndAnswersThem() throws Exception {
        answers("""
            {"UnprocessedItems": {"authors": [{"PutRequest": {"Item": {"id": {"S": "a1"}}}}]}}
            """);

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "BatchPutItem", "tables": {
                   "posts": [{"id": {"S": "p1"}, "title": {"S": "one"}}],
                   "authors": [{"id": {"S": "a1"}}]}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("BatchWriteItem");
        assertEquals("one", sent.path("RequestItems").path("posts").get(0)
                .path("PutRequest").path("Item").path("title").path("S").asText());
        assertEquals(Map.of("posts", List.of(Map.of("id", "p1", "title", "one")),
                "authors", Collections.singletonList(null)), result.get("data"));
        assertEquals(Map.of("posts", List.of(), "authors", List.of(Map.of("id", "a1"))),
                result.get("unprocessedItems"));
    }

    @Test
    void batchDeleteItemAnswersTheKeysAndNeverTheDeletedItems() throws Exception {
        answers("{\"UnprocessedItems\": {}}");

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "BatchDeleteItem", "tables": {"posts": [{"id": {"S": "p1"}}]}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("BatchWriteItem");
        assertEquals("p1", sent.path("RequestItems").path("posts").get(0)
                .path("DeleteRequest").path("Key").path("id").path("S").asText());
        assertEquals(Map.of("posts", List.of(Map.of("id", "p1"))), result.get("data"));
        assertEquals(Map.of("posts", List.of()), result.get("unprocessedKeys"));
    }

    @Test
    void aBatchWithNoTablesIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"BatchGetItem\", \"tables\": {}}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("tables"), e.getMessage());
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    @Test
    void transactWriteItemsSendsEachItemToItsOwnTableAndAnswersTheKeys() throws Exception {
        answers("{}");

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "TransactWriteItems", "transactItems": [
                   {"table": "posts", "operation": "PutItem", "key": {"id": {"S": "p1"}},
                    "attributeValues": {"title": {"S": "one"}},
                    "condition": {"expression": "attribute_not_exists(id)"}},
                   {"table": "authors", "operation": "UpdateItem", "key": {"id": {"S": "a1"}},
                    "update": {"expression": "SET #n = :n", "expressionNames": {"#n": "name"},
                               "expressionValues": {":n": {"S": "Ana"}}},
                    "condition": {"expression": "#v = :v", "expressionNames": {"#v": "version"},
                                  "expressionValues": {":v": {"N": "1"}},
                                  "returnValuesOnConditionCheckFailure": false}},
                   {"table": "posts", "operation": "DeleteItem", "key": {"id": {"S": "p0"}}},
                   {"table": "authors", "operation": "ConditionCheck", "key": {"id": {"S": "a2"}},
                    "condition": {"expression": "attribute_exists(id)"}}]}
                """), "eu-west-1");

        JsonNode sent = captureRequest("TransactWriteItems").path("TransactItems");
        JsonNode put = sent.get(0).path("Put");
        assertEquals("posts", put.path("TableName").asText());
        assertEquals("one", put.path("Item").path("title").path("S").asText());
        assertEquals("p1", put.path("Item").path("id").path("S").asText());
        assertEquals("attribute_not_exists(id)", put.path("ConditionExpression").asText());
        // AppSync asks for the standing item by default.
        assertEquals("ALL_OLD", put.path("ReturnValuesOnConditionCheckFailure").asText());
        JsonNode update = sent.get(1).path("Update");
        assertEquals("authors", update.path("TableName").asText());
        assertEquals("SET #n = :n", update.path("UpdateExpression").asText());
        assertEquals("#v = :v", update.path("ConditionExpression").asText());
        // The update's and the condition's placeholders share the item's one map.
        assertEquals("name", update.path("ExpressionAttributeNames").path("#n").asText());
        assertEquals("version", update.path("ExpressionAttributeNames").path("#v").asText());
        assertEquals("1", update.path("ExpressionAttributeValues").path(":v").path("N").asText());
        assertTrue(update.path("ReturnValuesOnConditionCheckFailure").isMissingNode(),
                "an item that asked for no standing item gets none");
        assertEquals("p0", sent.get(2).path("Delete").path("Key").path("id").path("S").asText());
        assertEquals("attribute_exists(id)", sent.get(3).path("ConditionCheck").path("ConditionExpression").asText());

        assertEquals(List.of(Map.of("id", "p1"), Map.of("id", "a1"), Map.of("id", "p0"), Map.of("id", "a2")),
                result.get("keys"));
        assertTrue(result.containsKey("cancellationReasons"));
        assertNull(result.get("cancellationReasons"));
    }

    @Test
    void aCancelledTransactionKeepsItsReasonsBesideTheError() throws Exception {
        when(handler.handle(anyString(), any(), anyString())).thenReturn(Response.status(400).entity("""
                {"__type": "com.amazonaws.dynamodb.v20120810#TransactionCanceledException",
                 "message": "Transaction cancelled, please refer cancellation reasons for specific reasons [ConditionalCheckFailed, None]",
                 "CancellationReasons": [
                   {"Code": "ConditionalCheckFailed", "Message": "The conditional request failed",
                    "Item": {"id": {"S": "p1"}, "title": {"S": "standing"}}},
                   {"Code": "None"}]}
                """).build());

        AppSyncDataSourceException e = assertThrows(AppSyncDataSourceException.class,
                () -> invoker.invoke(dataSource(), request("""
                {"operation": "TransactWriteItems", "transactItems": [
                   {"table": "posts", "operation": "PutItem", "key": {"id": {"S": "p1"}},
                    "condition": {"expression": "attribute_not_exists(id)"}},
                   {"table": "posts", "operation": "DeleteItem", "key": {"id": {"S": "p2"}}}]}
                """), "eu-west-1"));

        assertEquals("DynamoDB:TransactionCanceledException", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("Transaction cancelled"), e.getMessage());
        Map<?, ?> result = (Map<?, ?>) e.result();
        assertTrue(result.containsKey("keys"));
        assertNull(result.get("keys"));
        assertEquals(List.of(
                Map.of("item", Map.of("id", "p1", "title", "standing"),
                        "type", "ConditionalCheckFailed", "message", "The conditional request failed"),
                Map.of("type", "None", "message", "None")), result.get("cancellationReasons"));
    }

    @Test
    void transactGetItemsAnswersOneItemPerRequestItemInOrder() throws Exception {
        answers("""
            {"Responses": [{"Item": {"id": {"S": "p1"}}}, {}]}
            """);

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "TransactGetItems", "transactItems": [
                   {"table": "posts", "key": {"id": {"S": "p1"}}},
                   {"table": "authors", "key": {"id": {"S": "a9"}},
                    "projection": {"expression": "#n", "expressionNames": {"#n": "name"}}}]}
                """), "eu-west-1");

        JsonNode sent = captureRequest("TransactGetItems").path("TransactItems");
        assertEquals("authors", sent.get(1).path("Get").path("TableName").asText());
        assertEquals("#n", sent.get(1).path("Get").path("ProjectionExpression").asText());
        assertEquals(Arrays.asList(Map.of("id", "p1"), null), result.get("items"));
        assertTrue(result.containsKey("cancellationReasons"));
    }

    @Test
    void aTransactionItemThatNamesNoTableIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(), request("""
                {"operation": "TransactWriteItems", "transactItems": [
                   {"operation": "DeleteItem", "key": {"id": {"S": "p2"}}}]}
                """), "eu-west-1"));

        assertTrue(e.getMessage().contains("table"), e.getMessage());
    }

    @Test
    void aDataSourceWithNoTableNameFailsClearly() {
        DataSource ds = dataSource();
        ds.setDynamodbConfig(Map.of("awsRegion", "eu-west-1"));

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(ds,
                request("{\"operation\": \"GetItem\", \"key\": {}}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("tableName"), e.getMessage());
    }

    /** Drops the null-valued entries a NULL attribute produces, for a tidy comparison. */
    private Object stripNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (v != null) {
                    copy.put(k, stripNulls(v));
                }
            });
            return copy;
        }
        return value;
    }
}
