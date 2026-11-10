package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnStackWaits.StackState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::S3::Bucket}'s {@code ReplicationConfiguration}, declared under an {@code Fn::If}
 * that drops it with {@code AWS::NoValue}, reaches the bucket as its replication configuration, which
 * GetBucketReplication reads back; an update that drops the property deletes the configuration.
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
    void theTemplatesReplicationConfigurationReachesTheBucketAndAnUpdateDroppingItDeletesIt() {
        cloudFormation("CreateStack", "true");
        StackState created = CfnStackWaits.awaitTerminal(STACK);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());

        given().when().get("/" + SOURCE + "?replication").then().statusCode(200)
                .body(containsString("<Bucket>arn:aws:s3:::" + REPLICA + "</Bucket>"))
                .body(containsString("<Prefix>templates/</Prefix>"))
                .body(containsString("<DeleteMarkerReplication><Status>Disabled</Status>"))
                .body(containsString("<Role>arn:aws:iam::000000000000:role/"));

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
}
