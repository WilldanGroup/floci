package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnStackWaits.StackState;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeRegistryResponse;
import software.amazon.awssdk.services.ecr.model.ReplicationRule;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands the registry replication rule Switchboard's foundation declares, every repository under a
 * prefix copied to the class's second region, reads it back with DescribeRegistry, updates its
 * filter in place and deletes the stack, which clears the registry's rules. Runs in an account and
 * region of its own, since the configuration is the registry's.
 */
@QuarkusTest
class EcrReplicationConfigurationCfnIntegrationTest {

    private static final String ACCOUNT = "741682800001";
    private static final String REGION = "eu-central-1";
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261110/" + REGION + "/cloudformation/aws4_request";
    private static final String STACK = "ecr-replication-cfn-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Prefix": {"Type": "String"}},
          "Resources": {
            "Replication": {
              "Type": "AWS::ECR::ReplicationConfiguration",
              "Properties": {
                "ReplicationConfiguration": {
                  "Rules": [{
                    "Destinations": [{"Region": "eu-west-1", "RegistryId": {"Ref": "AWS::AccountId"}}],
                    "RepositoryFilters": [{"Filter": {"Ref": "Prefix"}, "FilterType": "PREFIX_MATCH"}]
                  }]
                }
              }
            }
          },
          "Outputs": {
            "ReplicationRef": {"Value": {"Ref": "Replication"}},
            "RegistryId": {"Value": {"Fn::GetAtt": ["Replication", "RegistryId"]}}
          }
        }
        """;

    @Test
    void standsUpdatesAndClearsTheRegistrysReplicationRules() {
        try (EcrClient ecr = AwsTestClients.create(EcrClient.builder(), ACCOUNT, Region.of(REGION))) {
            cloudFormation("CreateStack", "switchboard");
            StackState created = CfnStackWaits.awaitTerminal(STACK, CFN_AUTH);
            assertEquals("CREATE_COMPLETE", created.status(), created.reason());
            Map<String, String> outputs = outputs();
            assertEquals(ACCOUNT, outputs.get("ReplicationRef"));
            assertEquals(ACCOUNT, outputs.get("RegistryId"));

            DescribeRegistryResponse registry = ecr.describeRegistry(request -> { });
            assertEquals(ACCOUNT, registry.registryId());
            List<ReplicationRule> rules = registry.replicationConfiguration().rules();
            assertEquals(1, rules.size());
            assertEquals("eu-west-1", rules.get(0).destinations().get(0).region());
            assertEquals(ACCOUNT, rules.get(0).destinations().get(0).registryId());
            assertEquals("switchboard", rules.get(0).repositoryFilters().get(0).filter());
            assertEquals("PREFIX_MATCH", rules.get(0).repositoryFilters().get(0).filterTypeAsString());

            cloudFormation("UpdateStack", "parts");
            StackState updated = CfnStackWaits.awaitTerminal(STACK, CFN_AUTH);
            assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
            assertEquals("parts", ecr.describeRegistry(request -> { })
                    .replicationConfiguration().rules().get(0).repositoryFilters().get(0).filter());

            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(STACK, CFN_AUTH);
            assertEquals(List.of(), ecr.describeRegistry(request -> { }).replicationConfiguration().rules());
        }
    }

    private static void cloudFormation(String action, String prefix) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (prefix != null) {
            request.formParam("TemplateBody", TEMPLATE)
                    .formParam("Parameters.member.1.ParameterKey", "Prefix")
                    .formParam("Parameters.member.1.ParameterValue", prefix);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs() {
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }
}
