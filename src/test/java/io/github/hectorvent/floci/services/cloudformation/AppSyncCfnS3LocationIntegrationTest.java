package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deploys an AppSync API whose schema and {@code APPSYNC_JS} code a template names by
 * {@code DefinitionS3Location} and {@code CodeS3Location}, the {@code s3://<bucket>/<key>} Strings
 * the CloudFormation reference types them as, with the objects put in Floci's own S3 first, as
 * {@code aws cloudformation package} or a build that stages its files leaves them.
 *
 * <p>The control plane has to answer each object's text: the schema as the API's definition, and the
 * code as the function's and resolver's {@code code}, byte for byte, on the create and again on an
 * update that names other keys. A key the bucket does not hold fails the schema in S3's own words and
 * the stack rolls back, rather than standing an API with an empty schema.
 *
 * <p>Runs against the real GraphQL sidecar image, which compiles the schema, as
 * {@link AppSyncCfnIntegrationTest} does.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AppSyncCfnS3LocationIntegrationTest {

    @BeforeAll
    static void requireDockerAndTheSidecarImage() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.appsync.graphql-image");
    }

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/appsync/aws4_request";
    private static final String STACK = "appsync-cfn-s3-it";
    private static final String MISSING_STACK = "appsync-cfn-s3-missing-it";
    private static final String MISSING_API = "appsync-cfn-s3-missing-it-api";
    private static final String BUCKET = "appsync-cfn-s3-it-registry";

    private static final String SCHEMA_1 = "type Message { id: ID! }\ntype Query { getMessages: [Message] }\n";
    private static final String SCHEMA_2 =
            "type Message { id: ID! text: String }\ntype Query { getMessages: [Message] }\n";
    private static final String FUNCTION_CODE = """
        export function request(ctx) {
          return {};
        }
        export function response(ctx) {
          return ctx.result;
        }
        """;
    private static final String RESOLVER_CODE_1 = """
        export function request(ctx) { return {}; }
        export function response(ctx) { return [{ id: "1" }]; }
        """;
    private static final String RESOLVER_CODE_2 = """
        export function request(ctx) { return {}; }
        export function response(ctx) { return [{ id: "1", text: "from the second key" }]; }
        """;

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Bucket": {"Type": "String"},
            "SchemaKey": {"Type": "String"},
            "FunctionKey": {"Type": "String"},
            "ResolverKey": {"Type": "String"}
          },
          "Resources": {
            "GraphQlApi": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {"Name": "appsync-cfn-s3-it-api", "AuthenticationType": "API_KEY"}
            },
            "GraphQlSchema": {
              "Type": "AWS::AppSync::GraphQLSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "DefinitionS3Location": {"Fn::Sub": "s3://${Bucket}/${SchemaKey}"}
              }
            },
            "GraphQlDsNone": {
              "Type": "AWS::AppSync::DataSource",
              "DependsOn": "GraphQlSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Name": "none",
                "Type": "NONE"
              }
            },
            "GraphQlFnGetMessages": {
              "Type": "AWS::AppSync::FunctionConfiguration",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Name": "Query_getMessages_0",
                "DataSourceName": {"Fn::GetAtt": ["GraphQlDsNone", "Name"]},
                "Runtime": {"Name": "APPSYNC_JS", "RuntimeVersion": "1.0.0"},
                "CodeS3Location": {"Fn::Sub": "s3://${Bucket}/${FunctionKey}"}
              }
            },
            "GraphQlResolverQuerygetMessages": {
              "Type": "AWS::AppSync::Resolver",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "TypeName": "Query",
                "FieldName": "getMessages",
                "Kind": "PIPELINE",
                "Runtime": {"Name": "APPSYNC_JS", "RuntimeVersion": "1.0.0"},
                "CodeS3Location": {"Fn::Sub": "s3://${Bucket}/${ResolverKey}"},
                "PipelineConfig": {
                  "Functions": [{"Fn::GetAtt": ["GraphQlFnGetMessages", "FunctionId"]}]
                }
              }
            }
          },
          "Outputs": {
            "ApiId": {"Value": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]}},
            "FunctionId": {"Value": {"Fn::GetAtt": ["GraphQlFnGetMessages", "FunctionId"]}}
          }
        }
        """;

    private static final String MISSING_TEMPLATE = """
        {
          "Parameters": {"Location": {"Type": "String"}},
          "Resources": {
            "GraphQlApi": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {"Name": "%s", "AuthenticationType": "API_KEY"}
            },
            "GraphQlSchema": {
              "Type": "AWS::AppSync::GraphQLSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "DefinitionS3Location": {"Ref": "Location"}
              }
            }
          }
        }
        """.formatted(MISSING_API);

    @Test
    void theSchemaAndCodeStandFromTheObjectsTheirLocationsName() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        putObject("api/1.0.0/schema.graphql", SCHEMA_1);
        putObject("api/1.0.0/functions/Query.getMessages.0.js", FUNCTION_CODE);
        putObject("api/1.0.0/resolvers/Query.getMessages.js", RESOLVER_CODE_1);
        putObject("api/1.1.0/schema.graphql", SCHEMA_2);
        putObject("api/1.1.0/resolvers/Query.getMessages.js", RESOLVER_CODE_2);

        cloudFormation("CreateStack", STACK, TEMPLATE, Map.of(
                "Bucket", BUCKET,
                "SchemaKey", "api/1.0.0/schema.graphql",
                "FunctionKey", "api/1.0.0/functions/Query.getMessages.0.js",
                "ResolverKey", "api/1.0.0/resolvers/Query.getMessages.js"));
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        String created = describeStacks(STACK);
        String apiId = outputValue(created, "ApiId");
        String functionId = outputValue(created, "FunctionId");

        appSync("/v1/apis/" + apiId + "/schema").statusCode(200).body("schema.definition", equalTo(SCHEMA_1));
        appSync("/v1/apis/" + apiId + "/functions/" + functionId)
            .statusCode(200)
            .body("functionConfiguration.code", equalTo(FUNCTION_CODE));
        appSync("/v1/apis/" + apiId + "/types/Query/resolvers/getMessages")
            .statusCode(200)
            .body("resolver.kind", equalTo("PIPELINE"))
            .body("resolver.pipelineConfig.functions[0]", equalTo(functionId))
            .body("resolver.code", equalTo(RESOLVER_CODE_1));

        // Another release's keys: a schema with a field the resolver's new code returns, the
        // function's code where it was.
        cloudFormation("UpdateStack", STACK, TEMPLATE, Map.of(
                "Bucket", BUCKET,
                "SchemaKey", "api/1.1.0/schema.graphql",
                "FunctionKey", "api/1.0.0/functions/Query.getMessages.0.js",
                "ResolverKey", "api/1.1.0/resolvers/Query.getMessages.js"));
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");

        assertEquals(apiId, outputValue(describeStacks(STACK), "ApiId"));
        appSync("/v1/apis/" + apiId + "/schema")
            .statusCode(200)
            .body("schema.definition", equalTo(SCHEMA_2))
            .body("schema.definition", containsString("text: String"));
        appSync("/v1/apis/" + apiId + "/functions/" + functionId)
            .statusCode(200)
            .body("functionConfiguration.code", equalTo(FUNCTION_CODE));
        appSync("/v1/apis/" + apiId + "/types/Query/resolvers/getMessages")
            .statusCode(200)
            .body("resolver.code", equalTo(RESOLVER_CODE_2));

        cloudFormation("DeleteStack", STACK, null, Map.of());
        CfnStackWaits.awaitStackDeleted(STACK);
    }

    @Test
    void aSchemaWhoseObjectIsMissingFailsTheStackInS3sWords() {
        String bucket = BUCKET + "-missing";
        String location = "s3://" + bucket + "/api/1.0.0/schema.graphql";
        given().when().put("/" + bucket).then().statusCode(200);

        cloudFormation("CreateStack", MISSING_STACK, MISSING_TEMPLATE, Map.of("Location", location));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(MISSING_STACK, CFN_AUTH);

        assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
        String reason = CfnStackWaits.failedResourceReason(MISSING_STACK, CFN_AUTH);
        assertTrue(reason.contains("AWS::AppSync::GraphQLSchema"), reason);
        assertTrue(reason.contains("DefinitionS3Location"), reason);
        assertTrue(reason.contains(location), reason);
        assertTrue(reason.contains("NoSuchKey"), reason);
        // The rollback takes the API with it: nothing stands with an empty schema.
        List<String> names = given().header("Authorization", APPSYNC_AUTH).when().get("/v1/apis")
            .then().statusCode(200).extract().path("graphqlApis.name");
        assertFalse(names.contains(MISSING_API), names.toString());

        cloudFormation("DeleteStack", MISSING_STACK, null, Map.of());
        CfnStackWaits.awaitStackDeleted(MISSING_STACK, CFN_AUTH);
    }

    private static void putObject(String key, String text) {
        given().contentType("text/plain; charset=utf-8").body(text)
        .when().put("/" + BUCKET + "/" + key)
        .then().statusCode(200);
    }

    private static void cloudFormation(String action, String stackName, String template,
            Map<String, String> parameters) {
        RequestSpecification request = cfn(action, stackName).header("Authorization", CFN_AUTH);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stackName) {
        return cfn("DescribeStacks", stackName).header("Authorization", CFN_AUTH)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse appSync(String path) {
        return given().header("Authorization", APPSYNC_AUTH).when().get(path).then();
    }
}
