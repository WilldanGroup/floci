package io.floci.aws.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * CreateStackSet and UpdateStackSet hold a template to the limit of the parameter that carries it:
 * {@code TemplateBody} to 51,200 bytes, and the template a {@code TemplateURL} names to 1 MB
 * (CloudFormation API Reference, CreateStackSet and UpdateStackSet, TemplateBody and TemplateURL).
 * Switchboard's regional parts' StackSet template is about 81 KB and handed by URL.
 */
@QuarkusTest
class CloudFormationStackSetTemplateSizeIntegrationTest {

    /** A valid template of about {@code bytes} bytes: one handle and a padding string in its metadata. */
    private static String template(int bytes, String marker) {
        String head = "{\"Metadata\":{\"Marker\":\"" + marker + "\",\"Padding\":\"";
        String tail = "\"},\"Resources\":{\"Handle\":{\"Type\":\"AWS::CloudFormation::WaitConditionHandle\"}}}";
        return head + "x".repeat(Math.max(0, bytes - head.length() - tail.length())) + tail;
    }

    private static String stage(String bucket, String key, String body) {
        given().when().put("/" + bucket).then().statusCode(200);
        given().body(body).when().put("/" + bucket + "/" + key).then().statusCode(200);
        return "http://localhost:4566/" + bucket + "/" + key;
    }

    @Test
    void aStackSetIsCreatedAndUpdatedFromAnEightyKilobyteTemplateAtATemplateUrl() {
        String name = "url-size-" + UUID.randomUUID().toString().substring(0, 8);
        String bucket = "stackset-size-" + UUID.randomUUID().toString().substring(0, 8);
        String created = stage(bucket, "parts-1.json", template(81_000, "first"));
        String updated = stage(bucket, "parts-2.json", template(82_000, "second"));

        stackSets("CreateStackSet", name, "TemplateURL", created).statusCode(200)
                .body(containsString("<StackSetId>"));
        stackSets("UpdateStackSet", name, "TemplateURL", updated).statusCode(200)
                .body(containsString("<OperationId>"));
        stackSets("DescribeStackSet", name, null, null).statusCode(200)
                .body(containsString("second"));
    }

    @Test
    void aTemplateBodyOverItsLimitIsRefusedOnCreateAndUpdate() {
        String name = "body-size-" + UUID.randomUUID().toString().substring(0, 8);

        stackSets("CreateStackSet", name, "TemplateBody", template(51_201, "large")).statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("51200"));
        stackSets("CreateStackSet", name, "TemplateBody", template(51_200, "fits")).statusCode(200);
        stackSets("UpdateStackSet", name, "TemplateBody", template(51_201, "large")).statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("51200"));
    }

    @Test
    void aTemplateAtATemplateUrlOverOneMegabyteIsRefused() {
        String name = "url-limit-" + UUID.randomUUID().toString().substring(0, 8);
        String bucket = "stackset-limit-" + UUID.randomUUID().toString().substring(0, 8);
        String url = stage(bucket, "huge.json", template(1_048_577, "huge"));

        stackSets("CreateStackSet", name, "TemplateURL", url).statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("1048576"));
    }

    private static ValidatableResponse stackSets(String action, String name, String templateParameter,
                                                 String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackSetName", name);
        if (templateParameter != null) {
            request.formParam(templateParameter, template);
        }
        return request.when().post("/").then();
    }
}
