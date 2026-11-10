package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands the API's own hostname as Switchboard's API stack does: a GraphQL API, an
 * {@code AWS::AppSync::DomainName} and the {@code AWS::AppSync::DomainNameApiAssociation} that makes
 * the domain answer for the API. Reads every {@code Ref} and {@code Fn::GetAtt} value, the API's
 * {@code GraphQLEndpointArn} among them, and reads the domain and the association back through
 * GetDomainName and GetApiAssociation; updates the domain's description in place and deletes the
 * stack.
 */
@QuarkusTest
class AppSyncDomainNameCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/cloudformation/aws4_request";
    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261110/us-east-1/appsync/aws4_request";
    private static final String STACK = "appsync-domain-cfn-it";
    private static final String DOMAIN = "api.appsync-domain-cfn-it.example.com";
    private static final String DOMAIN_ARN = "arn:aws:appsync:us-east-1:000000000000:domainnames/" + DOMAIN;

    private static final String TEMPLATE = """
        {
          "Parameters": {"Description": {"Type": "String"}},
          "Resources": {
            "Api": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {"Name": "appsync-domain-cfn-it", "AuthenticationType": "API_KEY"}
            },
            "ApiDomainName": {
              "Type": "AWS::AppSync::DomainName",
              "Properties": {
                "DomainName": "%s",
                "CertificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555",
                "Description": {"Ref": "Description"}
              }
            },
            "ApiDomainAssociation": {
              "Type": "AWS::AppSync::DomainNameApiAssociation",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["Api", "ApiId"]},
                "DomainName": {"Ref": "ApiDomainName"}
              }
            }
          },
          "Outputs": {
            "ApiId": {"Value": {"Fn::GetAtt": ["Api", "ApiId"]}},
            "EndpointArn": {"Value": {"Fn::GetAtt": ["Api", "GraphQLEndpointArn"]}},
            "DomainRef": {"Value": {"Ref": "ApiDomainName"}},
            "DomainName": {"Value": {"Fn::GetAtt": ["ApiDomainName", "DomainName"]}},
            "DomainNameArn": {"Value": {"Fn::GetAtt": ["ApiDomainName", "DomainNameArn"]}},
            "AppSyncDomainName": {"Value": {"Fn::GetAtt": ["ApiDomainName", "AppSyncDomainName"]}},
            "HostedZoneId": {"Value": {"Fn::GetAtt": ["ApiDomainName", "HostedZoneId"]}},
            "AssociationRef": {"Value": {"Ref": "ApiDomainAssociation"}},
            "AssociationId": {"Value": {"Fn::GetAtt": ["ApiDomainAssociation", "ApiAssociationIdentifier"]}}
          }
        }
        """.formatted(DOMAIN);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void standsADomainNameAssociatedWithItsApi() {
        cloudFormation("CreateStack", "first");
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        Map<String, String> created = outputs();
        String apiId = created.get("ApiId");

        assertEquals("arn:aws:appsync:us-east-1:000000000000:endpoints/graphql-api/" + apiId,
                created.get("EndpointArn"));
        assertEquals(DOMAIN, created.get("DomainRef"));
        assertEquals(DOMAIN, created.get("DomainName"));
        assertEquals(DOMAIN_ARN, created.get("DomainNameArn"));
        assertEquals("Z2FDTNDATAQYW2", created.get("HostedZoneId"));
        assertEquals(DOMAIN_ARN + "/apiassociation", created.get("AssociationRef"));
        assertEquals(DOMAIN_ARN + "/apiassociation", created.get("AssociationId"));
        appSync("/v1/domainnames/" + DOMAIN)
                .statusCode(200)
                .body("domainNameConfig.domainNameArn", equalTo(DOMAIN_ARN))
                .body("domainNameConfig.appsyncDomainName", equalTo(created.get("AppSyncDomainName")))
                .body("domainNameConfig.hostedZoneId", equalTo("Z2FDTNDATAQYW2"))
                .body("domainNameConfig.description", equalTo("first"));
        appSync("/v1/domainnames/" + DOMAIN + "/apiassociation")
                .statusCode(200)
                .body("apiAssociation.apiId", equalTo(apiId))
                .body("apiAssociation.domainName", equalTo(DOMAIN));

        cloudFormation("UpdateStack", "second");
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");

        assertEquals(DOMAIN_ARN + "/apiassociation", outputs().get("AssociationRef"));
        appSync("/v1/domainnames/" + DOMAIN)
                .statusCode(200)
                .body("domainNameConfig.description", equalTo("second"));
        appSync("/v1/domainnames/" + DOMAIN + "/apiassociation")
                .statusCode(200)
                .body("apiAssociation.apiId", equalTo(apiId));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);

        appSync("/v1/domainnames/" + DOMAIN).statusCode(404);
        appSync("/v1/apis/" + apiId).statusCode(404);
    }

    private static void cloudFormation(String action, String description) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (description != null) {
            request.formParam("TemplateBody", TEMPLATE)
                    .formParam("Parameters.member.1.ParameterKey", "Description")
                    .formParam("Parameters.member.1.ParameterValue", description);
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

    private static ValidatableResponse appSync(String path) {
        return given().header("Authorization", APPSYNC_AUTH).when().get(path).then();
    }
}
