package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, a Query or Scan that names an index is authorized against the
 * index's ARN, {@code table/<table>/index/<index>}, and one that names none against the table's, as the
 * Service Authorization Reference gives both actions the {@code index} resource type beside {@code table}
 * (Actions, resources, and condition keys for Amazon DynamoDB). A role granted the index alone, the form a
 * policy takes to read a global secondary index and nothing else, reads the index and is refused the table,
 * and a role granted the table alone is refused its index.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbIndexArnEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:" + ACCOUNT + ":table/";
    private static final String INDEX = "GSI1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    private String table;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTableWithAnIndex() {
        table = "index-arn-" + UUID.randomUUID();
        cleanup.register(() -> accountCall("DeleteTable", "{\"TableName\":\"" + table + "\"}").statusCode(200));
        accountCall("CreateTable", """
                {"TableName":"%s",
                 "KeySchema":[{"AttributeName":"PK","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"},
                                         {"AttributeName":"GSI1PK","AttributeType":"S"}],
                 "GlobalSecondaryIndexes":[{"IndexName":"%s",
                   "KeySchema":[{"AttributeName":"GSI1PK","KeyType":"HASH"}],
                   "Projection":{"ProjectionType":"ALL"}}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table, INDEX)).statusCode(200);
        accountCall("PutItem", """
                {"TableName":"%s","Item":{"PK":{"S":"item"},"GSI1PK":{"S":"tenant"}}}
                """.formatted(table)).statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "Scan"})
    void aRoleGrantedTheIndexAloneReadsTheIndexAndIsRefusedTheTable(String action) {
        Session session = assumeRoleWith("dynamodb:" + action, TABLE_ARN_PREFIX + table + "/index/" + INDEX);

        read(session, action, true).statusCode(200).body("Count", equalTo(1));
        read(session, action, false).statusCode(400).body(containsString("AccessDeniedException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "Scan"})
    void aRoleGrantedTheTableAloneReadsTheTableAndIsRefusedItsIndex(String action) {
        Session session = assumeRoleWith("dynamodb:" + action, TABLE_ARN_PREFIX + table);

        read(session, action, false).statusCode(200).body("Count", equalTo(1));
        read(session, action, true).statusCode(400).body(containsString("AccessDeniedException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "Scan"})
    void aRoleGrantedEveryIndexOfTheTableReadsEach(String action) {
        Session session = assumeRoleWith("dynamodb:" + action, TABLE_ARN_PREFIX + table + "/index/*");

        read(session, action, true).statusCode(200).body("Count", equalTo(1));
    }

    /** A Query of the item's partition, or a Scan, of the index where {@code index}, else of the table. */
    private ValidatableResponse read(Session session, String action, boolean index) {
        String body = !"Query".equals(action) ? "{\"TableName\":\"" + table + "\"" + indexMember(index) + "}"
                : index ? """
                {"TableName":"%s","IndexName":"%s","KeyConditionExpression":"GSI1PK = :t",
                 "ExpressionAttributeValues":{":t":{"S":"tenant"}}}
                """.formatted(table, INDEX)
                : """
                {"TableName":"%s","KeyConditionExpression":"PK = :p",
                 "ExpressionAttributeValues":{":p":{"S":"item"}}}
                """.formatted(table);
        return given().port(endpoint.getPort()).header("Authorization", auth(session.accessKeyId(), "dynamodb"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }

    private static String indexMember(boolean index) {
        return index ? ",\"IndexName\":\"" + INDEX + "\"" : "";
    }

    /** A session of a new role that its own account may assume, allowed {@code action} on {@code resource}. */
    private Session assumeRoleWith(String action, String resource) {
        int port = endpoint.getPort();
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/",
                "index-arn-" + UUID.randomUUID().toString().substring(0, 8), """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"%s","Resource":"%s"}]}""".formatted(action, resource));
        return EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
    }

    /** A call as the account itself, which the cleanup also makes after RestAssured's port is reset. */
    private ValidatableResponse accountCall(String action, String body) {
        return given().port(endpoint.getPort()).header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
