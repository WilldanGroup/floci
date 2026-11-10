package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands the package domain Switchboard's foundation declares: a domain, four store repositories
 * each with one external connection, their {@code DomainName} taken from the domain's
 * {@code Fn::GetAtt Name}, and a repository whose {@code Upstreams} are the stores' {@code Name}s.
 * Reads each back through CodeArtifact's own API, updates the description, the upstreams, one
 * store's connection and the tags in place, and deletes the stack.
 */
@QuarkusTest
class CodeArtifactCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/cloudformation/aws4_request";
    private static final String CA_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/codeartifact/aws4_request";
    private static final String STACK = "codeartifact-cfn-it";
    private static final String DOMAIN = "cfn-it-packages";
    private static final String HUB = "cfn-it-packages";
    private static final String ACCOUNT = "000000000000";
    private static final String DOMAIN_ARN = "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":domain/" + DOMAIN;
    private static final String HUB_ARN =
            "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":repository/" + DOMAIN + "/" + HUB;
    private static final Map<String, String> STORES = Map.of(
            "cfn-it-npm-store", "public:npmjs",
            "cfn-it-pypi-store", "public:pypi",
            "cfn-it-maven-store", "public:maven-central",
            "cfn-it-nuget-store", "public:nuget-org");

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Phase": {"Type": "String"},
            "Team": {"Type": "String"}
          },
          "Conditions": {"Initial": {"Fn::Equals": [{"Ref": "Phase"}, "create"]}},
          "Resources": {
            "PackageDomain": {
              "Type": "AWS::CodeArtifact::Domain",
              "Properties": {
                "DomainName": "%1$s",
                "Tags": [{"Key": "team", "Value": {"Ref": "Team"}}]
              }
            },
            "NpmStore": {
              "Type": "AWS::CodeArtifact::Repository",
              "Properties": {
                "RepositoryName": "cfn-it-npm-store",
                "DomainName": {"Fn::GetAtt": ["PackageDomain", "Name"]},
                "ExternalConnections": ["public:npmjs"]
              }
            },
            "PypiStore": {
              "Type": "AWS::CodeArtifact::Repository",
              "Properties": {
                "RepositoryName": "cfn-it-pypi-store",
                "DomainName": {"Fn::GetAtt": ["PackageDomain", "Name"]},
                "ExternalConnections": ["public:pypi"]
              }
            },
            "MavenStore": {
              "Type": "AWS::CodeArtifact::Repository",
              "Properties": {
                "RepositoryName": "cfn-it-maven-store",
                "DomainName": {"Fn::GetAtt": ["PackageDomain", "Name"]},
                "ExternalConnections": ["public:maven-central"]
              }
            },
            "NugetStore": {
              "Type": "AWS::CodeArtifact::Repository",
              "Properties": {
                "RepositoryName": "cfn-it-nuget-store",
                "DomainName": {"Fn::GetAtt": ["PackageDomain", "Name"]},
                "ExternalConnections": {"Fn::If": ["Initial", ["public:nuget-org"], []]}
              }
            },
            "Packages": {
              "Type": "AWS::CodeArtifact::Repository",
              "Properties": {
                "RepositoryName": "%2$s",
                "DomainName": {"Fn::GetAtt": ["PackageDomain", "Name"]},
                "Description": {"Fn::Join": ["", ["packages ", {"Ref": "Phase"}]]},
                "Upstreams": {"Fn::If": ["Initial",
                  [{"Fn::GetAtt": ["NpmStore", "Name"]}, {"Fn::GetAtt": ["PypiStore", "Name"]},
                   {"Fn::GetAtt": ["MavenStore", "Name"]}, {"Fn::GetAtt": ["NugetStore", "Name"]}],
                  [{"Fn::GetAtt": ["NpmStore", "Name"]}, {"Fn::GetAtt": ["MavenStore", "Name"]}]]},
                "Tags": [{"Key": "team", "Value": {"Ref": "Team"}}]
              }
            }
          },
          "Outputs": {
            "DomainRef": {"Value": {"Ref": "PackageDomain"}},
            "DomainArn": {"Value": {"Fn::GetAtt": ["PackageDomain", "Arn"]}},
            "DomainName": {"Value": {"Fn::GetAtt": ["PackageDomain", "Name"]}},
            "DomainOwner": {"Value": {"Fn::GetAtt": ["PackageDomain", "Owner"]}},
            "DomainKey": {"Value": {"Fn::GetAtt": ["PackageDomain", "EncryptionKey"]}},
            "HubRef": {"Value": {"Ref": "Packages"}},
            "HubArn": {"Value": {"Fn::GetAtt": ["Packages", "Arn"]}},
            "HubName": {"Value": {"Fn::GetAtt": ["Packages", "Name"]}},
            "HubDomainName": {"Value": {"Fn::GetAtt": ["Packages", "DomainName"]}},
            "HubDomainOwner": {"Value": {"Fn::GetAtt": ["Packages", "DomainOwner"]}}
          }
        }
        """.formatted(DOMAIN, HUB);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void standsUpdatesAndDeletesADomainWithStoresAndAnUpstreamHub() {
        cloudFormation("CreateStack", "create", "platform");
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        Map<String, String> created = outputs();

        assertEquals(DOMAIN_ARN, created.get("DomainRef"));
        assertEquals(DOMAIN_ARN, created.get("DomainArn"));
        assertEquals(DOMAIN, created.get("DomainName"));
        assertEquals(ACCOUNT, created.get("DomainOwner"));
        assertEquals(HUB_ARN, created.get("HubRef"));
        assertEquals(HUB_ARN, created.get("HubArn"));
        assertEquals(HUB, created.get("HubName"));
        assertEquals(DOMAIN, created.get("HubDomainName"));
        assertEquals(ACCOUNT, created.get("HubDomainOwner"));

        describeDomain()
                .statusCode(200)
                .body("domain.arn", equalTo(DOMAIN_ARN))
                .body("domain.owner", equalTo(ACCOUNT))
                .body("domain.encryptionKey", equalTo(created.get("DomainKey")))
                .body("domain.repositoryCount", equalTo(5));
        for (Map.Entry<String, String> store : STORES.entrySet()) {
            describeRepository(store.getKey())
                    .statusCode(200)
                    .body("repository.externalConnections.externalConnectionName", contains(store.getValue()))
                    .body("repository.upstreams", empty());
        }
        describeRepository(HUB)
                .statusCode(200)
                .body("repository.arn", equalTo(HUB_ARN))
                .body("repository.description", equalTo("packages create"))
                .body("repository.upstreams.repositoryName", contains(
                        "cfn-it-npm-store", "cfn-it-pypi-store", "cfn-it-maven-store", "cfn-it-nuget-store"))
                .body("repository.externalConnections", empty());
        tags(DOMAIN_ARN).body("tags.key", contains("team")).body("tags.value", contains("platform"));

        cloudFormation("UpdateStack", "update", "data");
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");
        Map<String, String> updated = outputs();

        assertEquals(DOMAIN_ARN, updated.get("DomainRef"));
        assertEquals(HUB_ARN, updated.get("HubRef"));
        describeRepository(HUB)
                .statusCode(200)
                .body("repository.description", equalTo("packages update"))
                .body("repository.upstreams.repositoryName", contains("cfn-it-npm-store", "cfn-it-maven-store"));
        describeRepository("cfn-it-nuget-store")
                .statusCode(200)
                .body("repository.externalConnections", empty());
        describeRepository("cfn-it-npm-store")
                .statusCode(200)
                .body("repository.externalConnections.externalConnectionName", contains("public:npmjs"));
        tags(DOMAIN_ARN).body("tags.value", contains("data"));
        tags(HUB_ARN).body("tags.value", contains("data"));

        cloudFormation("DeleteStack", null, null);
        CfnStackWaits.awaitStackDeleted(STACK);

        for (String repository : List.of(HUB, "cfn-it-npm-store", "cfn-it-pypi-store", "cfn-it-maven-store",
                "cfn-it-nuget-store")) {
            describeRepository(repository).statusCode(404);
        }
        describeDomain().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void cloudFormation(String action, String phase, String team) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (phase != null) {
            request.formParam("TemplateBody", TEMPLATE)
                    .formParam("Parameters.member.1.ParameterKey", "Phase")
                    .formParam("Parameters.member.1.ParameterValue", phase)
                    .formParam("Parameters.member.2.ParameterKey", "Team")
                    .formParam("Parameters.member.2.ParameterValue", team);
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

    private static ValidatableResponse describeDomain() {
        return given().header("Authorization", CA_AUTH).get("/v1/domain?domain=" + DOMAIN).then();
    }

    private static ValidatableResponse describeRepository(String repository) {
        return given().header("Authorization", CA_AUTH)
                .get("/v1/repository?domain=" + DOMAIN + "&repository=" + repository).then();
    }

    private static ValidatableResponse tags(String arn) {
        return given().contentType("application/json").header("Authorization", CA_AUTH)
                .post("/v1/tags?resourceArn=" + arn).then().statusCode(200);
    }
}
