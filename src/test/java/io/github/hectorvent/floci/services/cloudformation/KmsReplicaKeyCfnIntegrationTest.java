package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.CfnStackWaits.StackState;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands a multi-Region primary key with {@code AWS::KMS::Key} in us-east-1 and its replica with
 * {@code AWS::KMS::ReplicaKey} in us-west-2, as Switchboard's class key and its replica are stood.
 * Encrypts under the primary in its region and decrypts under the replica in the other, reads the
 * replica's {@code Ref} and {@code Fn::GetAtt} values and its key metadata back, updates the
 * replica's description, policy and {@code Enabled} in place and deletes both stacks.
 */
@QuarkusTest
class KmsReplicaKeyCfnIntegrationTest {

    private static final String PRIMARY_STACK = "kms-replica-cfn-it-primary";
    private static final String REPLICA_STACK = "kms-replica-cfn-it-replica";
    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String PRIMARY_TEMPLATE = """
        {
          "Resources": {
            "ClassKey": {
              "Type": "AWS::KMS::Key",
              "Properties": {
                "Description": "class key",
                "MultiRegion": true,
                "KeyPolicy": {"Version": "2012-10-17", "Statement": [{"Sid": "Root", "Effect": "Allow",
                  "Principal": {"AWS": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:root"}},
                  "Action": "kms:*", "Resource": "*"}]}
              }
            }
          },
          "Outputs": {
            "KeyArn": {"Value": {"Fn::GetAtt": ["ClassKey", "Arn"]}},
            "KeyId": {"Value": {"Ref": "ClassKey"}}
          }
        }
        """;

    private static final String REPLICA_TEMPLATE = """
        {
          "Parameters": {
            "PrimaryKeyArn": {"Type": "String"},
            "Description": {"Type": "String"},
            "Enabled": {"Type": "String"}
          },
          "Resources": {
            "ClassKeyReplica": {
              "Type": "AWS::KMS::ReplicaKey",
              "DeletionPolicy": "Retain",
              "Properties": {
                "PrimaryKeyArn": {"Ref": "PrimaryKeyArn"},
                "Description": {"Ref": "Description"},
                "Enabled": {"Ref": "Enabled"},
                "KeyPolicy": {"Version": "2012-10-17", "Statement": [{"Sid": {"Ref": "Description"},
                  "Effect": "Allow",
                  "Principal": {"AWS": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:root"}},
                  "Action": "kms:*", "Resource": "*"}]},
                "Tags": [{"Key": "role", "Value": "class-key"}]
              }
            }
          },
          "Outputs": {
            "ReplicaRef": {"Value": {"Ref": "ClassKeyReplica"}},
            "ReplicaKeyId": {"Value": {"Fn::GetAtt": ["ClassKeyReplica", "KeyId"]}},
            "ReplicaArn": {"Value": {"Fn::GetAtt": ["ClassKeyReplica", "Arn"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void replicaInAnotherRegionDecryptsWhatThePrimaryEncrypted() {
        cloudFormation("us-east-1", "CreateStack", PRIMARY_STACK, PRIMARY_TEMPLATE, Map.of());
        assertEquals("CREATE_COMPLETE", await("us-east-1", PRIMARY_STACK).status());
        Map<String, String> primary = outputs("us-east-1", PRIMARY_STACK);
        String keyId = primary.get("KeyId");
        assertThat(keyId, startsWith("mrk-"));
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/" + keyId, primary.get("KeyArn"));

        cloudFormation("us-west-2", "CreateStack", REPLICA_STACK, REPLICA_TEMPLATE, Map.of(
                "PrimaryKeyArn", primary.get("KeyArn"), "Description", "replica", "Enabled", "true"));
        StackState created = await("us-west-2", REPLICA_STACK);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());
        Map<String, String> replica = outputs("us-west-2", REPLICA_STACK);
        assertEquals(keyId, replica.get("ReplicaRef"));
        assertEquals(keyId, replica.get("ReplicaKeyId"));
        assertEquals("arn:aws:kms:us-west-2:000000000000:key/" + keyId, replica.get("ReplicaArn"));

        kms("us-west-2", "DescribeKey", "{\"KeyId\":\"" + keyId + "\"}")
                .body("KeyMetadata.Arn", equalTo(replica.get("ReplicaArn")))
                .body("KeyMetadata.Description", equalTo("replica"))
                .body("KeyMetadata.Enabled", equalTo(true))
                .body("KeyMetadata.MultiRegionConfiguration.MultiRegionKeyType", equalTo("REPLICA"))
                .body("KeyMetadata.MultiRegionConfiguration.PrimaryKey.Region", equalTo("us-east-1"));
        kms("us-west-2", "GetKeyPolicy", "{\"KeyId\":\"" + keyId + "\",\"PolicyName\":\"default\"}")
                .body("Policy", containsString("\"Sid\":\"replica\""));
        kms("us-west-2", "ListResourceTags", "{\"KeyId\":\"" + keyId + "\"}")
                .body("Tags[0].TagKey", equalTo("role"))
                .body("Tags[0].TagValue", equalTo("class-key"));

        String plaintext = Base64.getEncoder().encodeToString("class secret".getBytes(StandardCharsets.UTF_8));
        String ciphertext = kms("us-east-1", "Encrypt",
                "{\"KeyId\":\"" + primary.get("KeyArn") + "\",\"Plaintext\":\"" + plaintext + "\"}")
                .extract().path("CiphertextBlob");
        kms("us-west-2", "Decrypt", "{\"KeyId\":\"" + replica.get("ReplicaArn") + "\",\"CiphertextBlob\":\""
                + ciphertext + "\"}")
                .body("Plaintext", equalTo(plaintext))
                .body("KeyId", equalTo(replica.get("ReplicaArn")));

        cloudFormation("us-west-2", "UpdateStack", REPLICA_STACK, REPLICA_TEMPLATE, Map.of(
                "PrimaryKeyArn", primary.get("KeyArn"), "Description", "rotated", "Enabled", "false"));
        StackState updated = await("us-west-2", REPLICA_STACK);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        assertEquals(keyId, outputs("us-west-2", REPLICA_STACK).get("ReplicaRef"));
        kms("us-west-2", "DescribeKey", "{\"KeyId\":\"" + keyId + "\"}")
                .body("KeyMetadata.Description", equalTo("rotated"))
                .body("KeyMetadata.Enabled", equalTo(false));
        kms("us-west-2", "GetKeyPolicy", "{\"KeyId\":\"" + keyId + "\",\"PolicyName\":\"default\"}")
                .body("Policy", containsString("\"Sid\":\"rotated\""));

        cloudFormation("us-west-2", "DeleteStack", REPLICA_STACK, null, Map.of());
        CfnStackWaits.awaitStackDeleted(REPLICA_STACK, auth("us-west-2"));
        cloudFormation("us-east-1", "DeleteStack", PRIMARY_STACK, null, Map.of());
        CfnStackWaits.awaitStackDeleted(PRIMARY_STACK, auth("us-east-1"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=test/20261110/" + region + "/cloudformation/aws4_request";
    }

    private static StackState await(String region, String stack) {
        return CfnStackWaits.awaitTerminal(stack, auth(region));
    }

    private static void cloudFormation(String region, String action, String stack, String template,
                                       Map<String, String> parameters) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(region))
                .formParam("Action", action)
                .formParam("StackName", stack);
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

    private static Map<String, String> outputs(String region, String stack) {
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(region))
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse kms(String region, String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261110/" + region + "/kms/aws4_request")
                .header("X-Amz-Target", "TrentService." + action)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/").then().statusCode(200);
    }
}
