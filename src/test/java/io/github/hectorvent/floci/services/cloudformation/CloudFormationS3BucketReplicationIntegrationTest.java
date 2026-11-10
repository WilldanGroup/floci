package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.CfnStackWaits.StackState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@code AWS::S3::Bucket}'s {@code ReplicationConfiguration}, declared the way Switchboard's
 * regional part declares its registry's, under an {@code Fn::If} that drops it with
 * {@code AWS::NoValue}, reaches the bucket as its replication configuration, so an object written
 * under the rule's prefix is copied to the destination bucket; an update that drops the property
 * deletes the configuration.
 */
@QuarkusTest
class CloudFormationS3BucketReplicationIntegrationTest {

    private static final String STACK = "s3-bucket-replication-cfn-it";
    private static final String SOURCE = "s3-bucket-replication-cfn-it-source";
    private static final String REPLICA = "s3-bucket-replication-cfn-it-replica";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Replicates": {"Type": "String"}},
          "Conditions": {"HasReplica": {"Fn::Equals": [{"Ref": "Replicates"}, "true"]}},
          "Resources": {
            "ReplicationRole": {
              "Type": "AWS::IAM::Role",
              "Properties": {
                "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                  "Principal": {"Service": "s3.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
              }
            },
            "Replica": {
              "Type": "AWS::S3::Bucket",
              "Properties": {"BucketName": "%2$s", "VersioningConfiguration": {"Status": "Enabled"}}
            },
            "Artifacts": {
              "Type": "AWS::S3::Bucket",
              "Properties": {
                "BucketName": "%1$s",
                "VersioningConfiguration": {"Status": "Enabled"},
                "ReplicationConfiguration": {"Fn::If": ["HasReplica", {
                  "Role": {"Fn::GetAtt": ["ReplicationRole", "Arn"]},
                  "Rules": [{
                    "Id": "templates",
                    "Status": "Enabled",
                    "Priority": 1,
                    "Filter": {"Prefix": "templates/"},
                    "DeleteMarkerReplication": {"Status": "Disabled"},
                    "Destination": {"Bucket": {"Fn::GetAtt": ["Replica", "Arn"]}}
                  }]
                }, {"Ref": "AWS::NoValue"}]}
              }
            }
          }
        }
        """.formatted(SOURCE, REPLICA);

    @Test
    void theTemplatesReplicationConfigurationReplicatesAndAnUpdateDroppingItDeletesIt() {
        cloudFormation("CreateStack", "true");
        StackState created = CfnStackWaits.awaitTerminal(STACK);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());

        given().when().get("/" + SOURCE + "?replication").then().statusCode(200)
                .body(containsString("<Bucket>arn:aws:s3:::" + REPLICA + "</Bucket>"))
                .body(containsString("<Prefix>templates/</Prefix>"))
                .body(containsString("<DeleteMarkerReplication><Status>Disabled</Status>"));
        given().body("{}").when().put("/" + SOURCE + "/templates/app/1.0.0/parent.json").then().statusCode(200);
        awaitReplicationStatus(SOURCE, "templates/app/1.0.0/parent.json", "COMPLETED");
        assertEquals("REPLICA", given().when().head("/" + REPLICA + "/templates/app/1.0.0/parent.json")
                .then().statusCode(200).extract().header("x-amz-replication-status"));

        cloudFormation("UpdateStack", "false");
        StackState updated = CfnStackWaits.awaitTerminal(STACK);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        given().when().get("/" + SOURCE + "?replication").then().statusCode(404)
                .body(containsString("ReplicationConfigurationNotFoundError"));
    }

    private static void cloudFormation(String action, String replicates) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", STACK)
                .formParam("TemplateBody", TEMPLATE)
                .formParam("Parameters.member.1.ParameterKey", "Replicates")
                .formParam("Parameters.member.1.ParameterValue", replicates);
        request.when().post("/").then().statusCode(200);
    }

    private static void awaitReplicationStatus(String bucket, String key, String expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = given().when().head("/" + bucket + "/" + key).then().statusCode(200).extract()
                    .header("x-amz-replication-status");
            if (expected.equals(last)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + bucket + "/" + key, e);
            }
        }
        fail(bucket + "/" + key + " did not read " + expected + " within ten seconds, last " + last);
    }
}
