package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.TransactionCanceledException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code AMAZON_DYNAMODB} data source.
 *
 * <p>Translates AppSync's request objects, {@code {operation: "GetItem", key: …}} and friends,
 * into the native DynamoDB API and back, going through {@link DynamoDbJsonHandler} so the whole of
 * Floci's DynamoDB behaviour applies: expressions, conditions, indexes, pagination.
 *
 * <p>The translation that matters is on the way out. A resolver's request carries DynamoDB
 * attribute values, because {@code util.dynamodb.toMapValues} put them there, but the result AppSync
 * hands back is <em>plain JSON</em>, {@code {"id": "7"}}, never {@code {"id": {"S": "7"}}}. A
 * resolver returning the raw response would give every field the wrong shape, so items are
 * unmarshalled here.
 *
 * <p>{@code nextToken} is likewise AppSync's own: an opaque string, made here by base64-encoding
 * the {@code LastEvaluatedKey} and decoded again on the way in.
 *
 * <p>The batch and transaction operations name their tables in the request, never the data
 * source's, and answer per table or per item in request order. An error DynamoDB answers reaches
 * the resolver under the data source's prefix, {@code DynamoDB:ConditionalCheckFailedException},
 * as AppSync names it; a cancelled transaction keeps its result beside the error, so the response
 * handler can read {@code ctx.result.cancellationReasons}.
 */
@ApplicationScoped
public class DynamoDbDataSourceInvoker implements AppSyncDataSourceInvoker {

    /** The data source's prefix AppSync gives an error DynamoDB answered. */
    static final String ERROR_PREFIX = "DynamoDB:";

    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final ObjectMapper objectMapper;

    @Inject
    public DynamoDbDataSourceInvoker(DynamoDbJsonHandler dynamoDbJsonHandler, ObjectMapper objectMapper) {
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.AMAZON_DYNAMODB;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        JsonNode appSyncRequest = objectMapper.valueToTree(request == null ? Map.of() : request);
        String operation = appSyncRequest.path("operation").asText("");
        return switch (operation) {
            case "GetItem" -> getItem(tableName(dataSource), appSyncRequest, region);
            case "PutItem" -> putItem(tableName(dataSource), appSyncRequest, region);
            case "UpdateItem" -> updateItem(tableName(dataSource), appSyncRequest, region);
            case "DeleteItem" -> deleteItem(tableName(dataSource), appSyncRequest, region);
            case "Query" -> queryOrScan("Query", tableName(dataSource), appSyncRequest, region);
            case "Scan" -> queryOrScan("Scan", tableName(dataSource), appSyncRequest, region);
            case "BatchGetItem" -> batchGetItem(appSyncRequest, region);
            case "BatchPutItem" -> batchPutItem(appSyncRequest, region);
            case "BatchDeleteItem" -> batchDeleteItem(appSyncRequest, region);
            case "TransactGetItems" -> transactGetItems(appSyncRequest, region);
            case "TransactWriteItems" -> transactWriteItems(appSyncRequest, region);
            case "" -> throw new AwsException("InternalFailureException",
                    "The resolver's DynamoDB request has no \"operation\"", 500);
            default -> throw new AwsException("InternalFailureException",
                    "Floci's AppSync DynamoDB data source does not implement the " + operation
                            + " operation yet", 500);
        };
    }

    // ── Operations ───────────────────────────────────────────────────────────

    private Object getItem(String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        if (request.path("consistentRead").asBoolean(false)) {
            native0.put("ConsistentRead", true);
        }
        applyProjection(native0, request.path("projection"));
        JsonNode response = call("GetItem", native0, region);
        // A miss is a null field, not an error: AppSync resolves the field to null.
        return unmarshalItem(response.get("Item"));
    }

    private Object putItem(String tableName, JsonNode request, String region) {
        ObjectNode item = objectMapper.createObjectNode();
        item.setAll((ObjectNode) objectMapper.valueToTree(fields(request.path("attributeValues"))));
        item.setAll((ObjectNode) objectMapper.valueToTree(fields(request.path("key"))));
        ObjectNode native0 = base(tableName);
        native0.set("Item", item);
        applyCondition(native0, request.path("condition"));
        call("PutItem", native0, region);
        // AppSync answers with the item as written, which is the merged key and attributes.
        return unmarshalItem(item);
    }

    private Object updateItem(String tableName, JsonNode request, String region) {
        JsonNode update = request.path("update");
        if (!update.hasNonNull("expression")) {
            throw new AwsException("InternalFailureException",
                    "An UpdateItem request needs update.expression", 500);
        }
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        native0.put("UpdateExpression", update.path("expression").asText());
        applyExpressionMaps(native0, update);
        applyCondition(native0, request.path("condition"));
        native0.put("ReturnValues", "ALL_NEW");
        JsonNode response = call("UpdateItem", native0, region);
        return unmarshalItem(response.get("Attributes"));
    }

    private Object deleteItem(String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        applyCondition(native0, request.path("condition"));
        native0.put("ReturnValues", "ALL_OLD");
        JsonNode response = call("DeleteItem", native0, region);
        // The deleted item, as AppSync reports it, so a mutation can return what it removed.
        return unmarshalItem(response.get("Attributes"));
    }

    private Object queryOrScan(String action, String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        if ("Query".equals(action)) {
            JsonNode query = request.path("query");
            if (!query.hasNonNull("expression")) {
                throw new AwsException("InternalFailureException",
                        "A Query request needs query.expression", 500);
            }
            native0.put("KeyConditionExpression", query.path("expression").asText());
            applyExpressionMaps(native0, query);
        }
        applyFilter(native0, request.path("filter"));
        if (request.hasNonNull("index")) {
            native0.put("IndexName", request.path("index").asText());
        }
        if (request.hasNonNull("limit")) {
            native0.put("Limit", request.path("limit").asInt());
        }
        if (request.has("scanIndexForward")) {
            native0.put("ScanIndexForward", request.path("scanIndexForward").asBoolean(true));
        }
        if (request.path("consistentRead").asBoolean(false)) {
            native0.put("ConsistentRead", true);
        }
        if (request.hasNonNull("nextToken")) {
            native0.set("ExclusiveStartKey", decodeNextToken(request.path("nextToken").asText()));
        }
        applyProjection(native0, request.path("projection"));

        JsonNode response = call(action, native0, region);
        List<Object> items = new ArrayList<>();
        for (JsonNode item : response.path("Items")) {
            items.add(unmarshalItem(item));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("scannedCount", response.path("ScannedCount").asInt(items.size()));
        result.put("nextToken", encodeNextToken(response.get("LastEvaluatedKey")));
        return result;
    }

    /**
     * Each table's items in the order its keys were asked, null where no item stands or the key went
     * unprocessed. DynamoDB answers a batch's items in no order, so each is placed by its key; a key
     * attribute a projection leaves out is asked for under a name of its own and dropped again.
     */
    private Object batchGetItem(JsonNode request, String region) {
        JsonNode tables = requireTables(request, "BatchGetItem");
        ObjectNode requestItems = objectMapper.createObjectNode();
        Map<String, List<String>> addedKeyNames = new LinkedHashMap<>();
        tables.fields().forEachRemaining(entry -> {
            JsonNode table = entry.getValue();
            ObjectNode read = objectMapper.createObjectNode();
            read.set("Keys", table.path("keys"));
            if (table.path("consistentRead").asBoolean(false)) {
                read.put("ConsistentRead", true);
            }
            applyProjection(read, table.path("projection"));
            addedKeyNames.put(entry.getKey(), projectKeys(read, table.path("keys")));
            requestItems.set(entry.getKey(), read);
        });
        ObjectNode native0 = objectMapper.createObjectNode();
        native0.set("RequestItems", requestItems);
        JsonNode response = call("BatchGetItem", native0, region);

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> unprocessedKeys = new LinkedHashMap<>();
        tables.fields().forEachRemaining(entry -> {
            String table = entry.getKey();
            List<JsonNode> found = new ArrayList<>();
            response.path("Responses").path(table).forEach(found::add);
            List<Object> items = new ArrayList<>();
            for (JsonNode key : entry.getValue().path("keys")) {
                JsonNode item = found.stream().filter(candidate -> hasKey(candidate, key))
                        .findFirst().orElse(null);
                items.add(item == null ? null : unmarshalItem(without(item, addedKeyNames.get(table))));
            }
            data.put(table, items);
            unprocessedKeys.put(table,
                    unmarshalAll(response.path("UnprocessedKeys").path(table).path("Keys")));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("unprocessedKeys", unprocessedKeys);
        return result;
    }

    /** Each table's items as written, in request order, null where the item went unprocessed. */
    private Object batchPutItem(JsonNode request, String region) {
        JsonNode tables = requireTables(request, "BatchPutItem");
        JsonNode unprocessed = batchWrite(tables, "PutRequest", "Item", region);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> unprocessedItems = new LinkedHashMap<>();
        tables.fields().forEachRemaining(entry -> {
            List<JsonNode> left = unprocessedWrites(unprocessed, entry.getKey(), "PutRequest", "Item");
            List<Object> items = new ArrayList<>();
            entry.getValue().forEach(item -> items.add(left.contains(item) ? null : unmarshalItem(item)));
            data.put(entry.getKey(), items);
            unprocessedItems.put(entry.getKey(), unmarshalAll(left));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("unprocessedItems", unprocessedItems);
        return result;
    }

    /**
     * Each table's keys as deleted, in request order, null where the key went unprocessed: unlike
     * DeleteItem, AppSync answers the key and never the deleted item.
     */
    private Object batchDeleteItem(JsonNode request, String region) {
        JsonNode tables = requireTables(request, "BatchDeleteItem");
        JsonNode unprocessed = batchWrite(tables, "DeleteRequest", "Key", region);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> unprocessedKeys = new LinkedHashMap<>();
        tables.fields().forEachRemaining(entry -> {
            List<JsonNode> left = unprocessedWrites(unprocessed, entry.getKey(), "DeleteRequest", "Key");
            List<Object> keys = new ArrayList<>();
            entry.getValue().forEach(key -> keys.add(left.contains(key) ? null : unmarshalItem(key)));
            data.put(entry.getKey(), keys);
            unprocessedKeys.put(entry.getKey(), unmarshalAll(left));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("unprocessedKeys", unprocessedKeys);
        return result;
    }

    /** One item per request item, in order, null where none stands. */
    private Object transactGetItems(JsonNode request, String region) {
        JsonNode transactItems = requireTransactItems(request, "TransactGetItems");
        ArrayNode reads = objectMapper.createArrayNode();
        for (JsonNode item : transactItems) {
            ObjectNode get = reads.addObject().putObject("Get");
            get.put("TableName", requireTable(item, "TransactGetItems"));
            get.set("Key", item.path("key"));
            applyProjection(get, item.path("projection"));
        }
        ObjectNode native0 = objectMapper.createObjectNode();
        native0.set("TransactItems", reads);
        JsonNode response = call("TransactGetItems", native0, region);
        List<Object> items = new ArrayList<>();
        response.path("Responses").forEach(entry -> items.add(unmarshalItem(entry.get("Item"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("cancellationReasons", null);
        return result;
    }

    /**
     * Each request item's key, in order. A condition that fails cancels the whole transaction, and
     * the standing item comes back in its cancellation reason unless the request item asked for it
     * not to, by {@code returnValuesOnConditionCheckFailure: false}.
     */
    private Object transactWriteItems(JsonNode request, String region) {
        JsonNode transactItems = requireTransactItems(request, "TransactWriteItems");
        ArrayNode writes = objectMapper.createArrayNode();
        List<Object> keys = new ArrayList<>();
        for (JsonNode item : transactItems) {
            ObjectNode write = objectMapper.createObjectNode();
            write.put("TableName", requireTable(item, "TransactWriteItems"));
            String operation = item.path("operation").asText("");
            switch (operation) {
                case "PutItem" -> {
                    ObjectNode written = objectMapper.createObjectNode();
                    written.setAll((ObjectNode) objectMapper.valueToTree(fields(item.path("attributeValues"))));
                    written.setAll((ObjectNode) objectMapper.valueToTree(fields(item.path("key"))));
                    write.set("Item", written);
                    writes.addObject().set("Put", write);
                }
                case "UpdateItem" -> {
                    JsonNode update = item.path("update");
                    if (!update.hasNonNull("expression")) {
                        throw new AwsException("InternalFailureException",
                                "A TransactWriteItems UpdateItem needs update.expression", 500);
                    }
                    write.set("Key", item.path("key"));
                    write.put("UpdateExpression", update.path("expression").asText());
                    applyExpressionMaps(write, update);
                    writes.addObject().set("Update", write);
                }
                case "DeleteItem" -> {
                    write.set("Key", item.path("key"));
                    writes.addObject().set("Delete", write);
                }
                case "ConditionCheck" -> {
                    if (!item.path("condition").hasNonNull("expression")) {
                        throw new AwsException("InternalFailureException",
                                "A TransactWriteItems ConditionCheck needs condition.expression", 500);
                    }
                    write.set("Key", item.path("key"));
                    writes.addObject().set("ConditionCheck", write);
                }
                default -> throw new AwsException("InternalFailureException",
                        "A TransactWriteItems item's operation is PutItem, UpdateItem, DeleteItem or "
                                + "ConditionCheck, not \"" + operation + "\"", 500);
            }
            applyCondition(write, item.path("condition"));
            if (write.has("ConditionExpression")
                    && item.path("condition").path("returnValuesOnConditionCheckFailure").asBoolean(true)) {
                write.put("ReturnValuesOnConditionCheckFailure", "ALL_OLD");
            }
            keys.add(unmarshalItem(item.path("key")));
        }
        ObjectNode native0 = objectMapper.createObjectNode();
        native0.set("TransactItems", writes);
        call("TransactWriteItems", native0, region);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", keys);
        result.put("cancellationReasons", null);
        return result;
    }

    // ── Request assembly ─────────────────────────────────────────────────────

    private ObjectNode base(String tableName) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("TableName", tableName);
        return request;
    }

    /**
     * The table a resolver's request runs against. AppSync takes it from the data source's
     * {@code dynamodbConfig}, and a request may not override it, so this is the one place the
     * mapping between the two spellings of that config member matters.
     */
    private String tableName(DataSource dataSource) {
        Map<String, Object> config = dataSource.getDynamodbConfig();
        Object tableName = config == null ? null : config.get("tableName");
        if (tableName == null || String.valueOf(tableName).isBlank()) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName() + " has no dynamodbConfig.tableName", 500);
        }
        return String.valueOf(tableName);
    }

    private void applyProjection(ObjectNode target, JsonNode projection) {
        if (projection == null || !projection.isObject()) {
            return;
        }
        if (projection.hasNonNull("expression")) {
            target.put("ProjectionExpression", projection.path("expression").asText());
        }
        applyExpressionMaps(target, projection);
    }

    private void applyFilter(ObjectNode target, JsonNode filter) {
        if (filter == null || !filter.isObject() || !filter.hasNonNull("expression")) {
            return;
        }
        target.put("FilterExpression", filter.path("expression").asText());
        applyExpressionMaps(target, filter);
    }

    private void applyCondition(ObjectNode target, JsonNode condition) {
        if (condition == null || !condition.isObject() || !condition.hasNonNull("expression")) {
            return;
        }
        target.put("ConditionExpression", condition.path("expression").asText());
        applyExpressionMaps(target, condition);
    }

    /**
     * Merges {@code expressionNames} / {@code expressionValues} into the native request. Merged
     * rather than set, because a Query can carry both a key condition and a filter and each brings
     * its own placeholders into the one shared map.
     */
    private void applyExpressionMaps(ObjectNode target, JsonNode source) {
        mergeInto(target, "ExpressionAttributeNames", source.path("expressionNames"));
        mergeInto(target, "ExpressionAttributeValues", source.path("expressionValues"));
    }

    private void mergeInto(ObjectNode target, String field, JsonNode addition) {
        if (addition == null || !addition.isObject() || addition.isEmpty()) {
            return;
        }
        ObjectNode existing = target.has(field) ? (ObjectNode) target.get(field) : objectMapper.createObjectNode();
        existing.setAll((ObjectNode) addition);
        target.set(field, existing);
    }

    private JsonNode requireTables(JsonNode request, String operation) {
        JsonNode tables = request.path("tables");
        if (!tables.isObject() || tables.isEmpty()) {
            throw new AwsException("InternalFailureException",
                    "A " + operation + " request needs at least one table in tables", 500);
        }
        return tables;
    }

    private JsonNode requireTransactItems(JsonNode request, String operation) {
        JsonNode transactItems = request.path("transactItems");
        if (!transactItems.isArray() || transactItems.isEmpty()) {
            throw new AwsException("InternalFailureException",
                    "A " + operation + " request needs at least one item in transactItems", 500);
        }
        return transactItems;
    }

    private String requireTable(JsonNode item, String operation) {
        String table = item.path("table").asText("");
        if (table.isBlank()) {
            throw new AwsException("InternalFailureException",
                    "Each item of a " + operation + " request names its table", 500);
        }
        return table;
    }

    /** Sends a BatchWriteItem of one request kind and answers its UnprocessedItems. */
    private JsonNode batchWrite(JsonNode tables, String requestKind, String member, String region) {
        ObjectNode requestItems = objectMapper.createObjectNode();
        tables.fields().forEachRemaining(entry -> {
            ArrayNode writes = requestItems.putArray(entry.getKey());
            entry.getValue().forEach(value -> writes.addObject().putObject(requestKind).set(member, value));
        });
        ObjectNode native0 = objectMapper.createObjectNode();
        native0.set("RequestItems", requestItems);
        return call("BatchWriteItem", native0, region).path("UnprocessedItems");
    }

    private List<JsonNode> unprocessedWrites(JsonNode unprocessed, String table, String requestKind,
                                             String member) {
        List<JsonNode> left = new ArrayList<>();
        unprocessed.path(table).forEach(write -> left.add(write.path(requestKind).path(member)));
        return left;
    }

    /**
     * Where a projection is asked for, adds each key attribute it leaves out under a placeholder of
     * Floci's own, so the item that comes back can be placed by its key, and answers the attributes
     * added, to be dropped again. A projection that already names a key attribute keeps it, since
     * DynamoDB refuses one that names a path twice.
     */
    private List<String> projectKeys(ObjectNode read, JsonNode keys) {
        List<String> added = new ArrayList<>();
        if (!read.hasNonNull("ProjectionExpression") || !keys.isArray() || keys.isEmpty()) {
            return added;
        }
        JsonNode names = read.path("ExpressionAttributeNames");
        List<String> projected = new ArrayList<>();
        for (String path : read.path("ProjectionExpression").asText().split(",")) {
            String top = path.trim().split("[.\\[]", 2)[0];
            projected.add(top.startsWith("#") ? names.path(top).asText(top) : top);
        }
        StringBuilder expression = new StringBuilder(read.path("ProjectionExpression").asText());
        ObjectNode placeholders = read.has("ExpressionAttributeNames")
                ? (ObjectNode) read.get("ExpressionAttributeNames") : objectMapper.createObjectNode();
        keys.get(0).fieldNames().forEachRemaining(attribute -> {
            if (!projected.contains(attribute)) {
                String placeholder = "#flociKey" + added.size();
                placeholders.put(placeholder, attribute);
                expression.append(", ").append(placeholder);
                added.add(attribute);
            }
        });
        if (!added.isEmpty()) {
            read.put("ProjectionExpression", expression.toString());
            read.set("ExpressionAttributeNames", placeholders);
        }
        return added;
    }

    private boolean hasKey(JsonNode item, JsonNode key) {
        if (!key.isObject() || key.isEmpty()) {
            return false;
        }
        Iterator<String> attributes = key.fieldNames();
        while (attributes.hasNext()) {
            String attribute = attributes.next();
            if (!sameValue(item.get(attribute), key.get(attribute))) {
                return false;
            }
        }
        return true;
    }

    /** Attribute values compared as DynamoDB compares keys: a number by its value, not its text. */
    private static boolean sameValue(JsonNode stored, JsonNode asked) {
        if (stored == null) {
            return false;
        }
        if (stored.has("N") && asked.has("N")) {
            try {
                return new BigDecimal(stored.get("N").asText()).compareTo(new BigDecimal(asked.get("N").asText())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return stored.equals(asked);
    }

    private JsonNode without(JsonNode item, List<String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return item;
        }
        ObjectNode copy = item.deepCopy();
        copy.remove(attributes);
        return copy;
    }

    private Map<String, JsonNode> fields(JsonNode node) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        }
        return fields;
    }

    // ── Response translation ─────────────────────────────────────────────────

    /**
     * One native call. What DynamoDB refuses, thrown or answered as an error body, reaches the
     * resolver as AppSync names it: DynamoDB's own code under the data source's prefix, the
     * {@code DynamoDB:ConditionalCheckFailedException} of AppSync's condition expressions reference.
     */
    private JsonNode call(String action, ObjectNode request, String region) {
        Response response;
        JsonNode body;
        try {
            response = dynamoDbJsonHandler.handle(action, request, region);
            body = body(response);
        } catch (TransactionCanceledException e) {
            throw cancelled(action, e.getMessage(), e.getHttpStatus(), reasonsOf(e));
        } catch (AwsException e) {
            throw new AwsException(ERROR_PREFIX + e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "The DynamoDB data source call failed: " + e.getMessage(), 500);
        }
        if (response.getStatus() < 300) {
            return body;
        }
        String type = body.path("__type").asText("InternalServerError");
        String code = type.substring(type.lastIndexOf('#') + 1);
        String message = body.hasNonNull("message") ? body.path("message").asText()
                : body.path("Message").asText(null);
        if ("TransactionCanceledException".equals(code)) {
            throw cancelled(action, message, response.getStatus(), body.path("CancellationReasons"));
        }
        throw new AwsException(ERROR_PREFIX + code, message, response.getStatus());
    }

    private JsonNode body(Response response) throws Exception {
        Object entity = response.getEntity();
        if (entity == null) {
            return objectMapper.createObjectNode();
        }
        if (entity instanceof String text) {
            return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
        }
        return objectMapper.valueToTree(entity);
    }

    /**
     * A cancelled transaction: the error, and beside it the result AppSync gives, the operation's
     * own member null and one cancellation reason per request item, in order, {@code None} where
     * the item was not the cause, and the standing item where a condition failed and it was asked
     * for.
     */
    private AppSyncDataSourceException cancelled(String action, String message, int status,
                                                 JsonNode cancellationReasons) {
        List<Object> reasons = new ArrayList<>();
        for (JsonNode reason : cancellationReasons) {
            Map<String, Object> answered = new LinkedHashMap<>();
            String type = reason.path("Code").asText("None");
            if (reason.hasNonNull("Item")) {
                answered.put("item", unmarshalItem(reason.get("Item")));
            }
            answered.put("type", type);
            String reasonMessage = reason.path("Message").asText("");
            answered.put("message", reasonMessage.isEmpty() ? type : reasonMessage);
            reasons.add(answered);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("TransactGetItems".equals(action) ? "items" : "keys", null);
        result.put("cancellationReasons", reasons);
        return new AppSyncDataSourceException(ERROR_PREFIX + "TransactionCanceledException", message,
                status, result);
    }

    private JsonNode reasonsOf(TransactionCanceledException e) {
        ArrayNode reasons = objectMapper.createArrayNode();
        for (TransactionCanceledException.CancellationReason reason : e.getCancellationReasons()) {
            ObjectNode answered = reasons.addObject();
            answered.put("Code", reason.code().isEmpty() ? "None" : reason.code());
            if (reason.message() != null) {
                answered.put("Message", reason.message());
            }
            if (reason.item() != null) {
                answered.set("Item", reason.item());
            }
        }
        return reasons;
    }

    private List<Object> unmarshalAll(Iterable<JsonNode> items) {
        List<Object> plain = new ArrayList<>();
        items.forEach(item -> plain.add(unmarshalItem(item)));
        return plain;
    }

    /** Attribute values to plain JSON, which is the only shape a GraphQL field can be built from. */
    private Object unmarshalItem(JsonNode item) {
        if (item == null || item.isNull() || !item.isObject()) {
            return null;
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        item.fields().forEachRemaining(entry -> plain.put(entry.getKey(), unmarshal(entry.getValue())));
        return plain;
    }

    private Object unmarshal(JsonNode value) {
        if (value == null || value.isNull() || !value.isObject()) {
            return null;
        }
        if (value.has("NULL")) {
            return null;
        }
        if (value.has("S")) {
            return value.get("S").asText();
        }
        if (value.has("N")) {
            return number(value.get("N").asText());
        }
        if (value.has("BOOL")) {
            return value.get("BOOL").asBoolean();
        }
        if (value.has("B")) {
            return value.get("B").asText();
        }
        if (value.has("SS")) {
            return toList(value.get("SS"));
        }
        if (value.has("NS")) {
            List<Object> numbers = new ArrayList<>();
            value.get("NS").forEach(element -> numbers.add(number(element.asText())));
            return numbers;
        }
        if (value.has("BS")) {
            return toList(value.get("BS"));
        }
        if (value.has("L")) {
            List<Object> list = new ArrayList<>();
            value.get("L").forEach(element -> list.add(unmarshal(element)));
            return list;
        }
        if (value.has("M")) {
            return unmarshalItem(value.get("M"));
        }
        return null;
    }

    /**
     * A DynamoDB number as the nearest Java type: integral where it can be, so an id field is 7
     * rather than 7.0 in the response and a GraphQL Int can be built from it.
     *
     * <p>Shared by the N and NS branches rather than written twice: the set branch kept returning
     * Double after the scalar one was fixed. Deliberately not a conditional expression either, since
     * one with Double and Long branches is subject to binary numeric promotion, which unboxes both
     * and makes every number a double again.
     */
    private Object number(String value) {
        try {
            if (value.contains(".") || value.contains("e") || value.contains("E")) {
                return Double.valueOf(value);
            }
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private List<Object> toList(JsonNode array) {
        List<Object> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private String encodeNextToken(JsonNode lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isNull() || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return Base64Url.encode(lastEvaluatedKey.toString());
    }

    private JsonNode decodeNextToken(String nextToken) {
        try {
            return objectMapper.readTree(Base64Url.decode(nextToken));
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "The nextToken handed to the DynamoDB data source is not one Floci issued", 500);
        }
    }
}
