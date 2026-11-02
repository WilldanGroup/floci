package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ListRuleNamesByTarget names the rules of one bus that have a target with the ARN asked for, as the EventBridge API
 * Reference's ListRuleNamesByTarget describes: the default bus where {@code EventBusName} is left out, a name or an
 * ARN where it is given, at most {@code Limit} names a page with a {@code NextToken} for the rest, and
 * {@code TargetArn} required.
 */
@QuarkusTest
class EventBridgeListRuleNamesByTargetIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";

    private String suffix;
    private String queue;
    private String topic;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void names() {
        suffix = Long.toString(System.nanoTime(), 36);
        queue = "arn:aws:sqs:us-east-1:000000000000:rnbt-queue-" + suffix;
        topic = "arn:aws:sns:us-east-1:000000000000:rnbt-topic-" + suffix;
    }

    @Test
    void namesEachRuleOfTheDefaultBusWithATargetOfThatArn() {
        rule("a-" + suffix, null, queue);
        rule("b-" + suffix, null, topic);
        rule("c-" + suffix, null, topic, queue);

        assertEquals(List.of("a-" + suffix, "c-" + suffix), names("{\"TargetArn\":\"" + queue + "\"}"));
        assertEquals(List.of("b-" + suffix, "c-" + suffix), names("{\"TargetArn\":\"" + topic + "\"}"));
    }

    @Test
    void readsTheBusNamedByNameOrArnAndNoOther() {
        String bus = "rnbt-bus-" + suffix;
        call("CreateEventBus", "{\"Name\":\"" + bus + "\"}");
        rule("on-bus-" + suffix, bus, queue);
        rule("on-default-" + suffix, null, queue);

        assertEquals(List.of("on-default-" + suffix), names("{\"TargetArn\":\"" + queue + "\"}"));
        assertEquals(List.of("on-bus-" + suffix),
                names("{\"TargetArn\":\"" + queue + "\",\"EventBusName\":\"" + bus + "\"}"));
        assertEquals(List.of("on-bus-" + suffix), names("{\"TargetArn\":\"" + queue
                + "\",\"EventBusName\":\"arn:aws:events:us-east-1:000000000000:event-bus/" + bus + "\"}"));
    }

    @Test
    void aTargetNoRuleHasIsAnsweredNoNames() {
        assertEquals(List.of(), names("{\"TargetArn\":\"" + queue + "\"}"));
    }

    @Test
    void limitOnePagesThroughEveryNameOnce() {
        rule("p3-" + suffix, null, queue);
        rule("p1-" + suffix, null, queue);
        rule("p2-" + suffix, null, queue);

        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            String tokenField = token == null ? "" : ",\"NextToken\":\"" + token + "\"";
            JsonPath page = list("{\"TargetArn\":\"" + queue + "\",\"Limit\":1" + tokenField + "}");
            List<String> names = page.getList("RuleNames");
            assertEquals(1, names.size());
            seen.add(names.get(0));
            token = page.getString("NextToken");
        } while (token != null);

        assertEquals(List.of("p1-" + suffix, "p2-" + suffix, "p3-" + suffix), seen);
    }

    @Test
    void aRequestWithoutTargetArnIsRefused() {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
                .body("{}")
                .when().post("/")
                .then().statusCode(400)
                .body(containsString("ValidationException"))
                .body(containsString("Value null at 'targetArn' failed to satisfy constraint: Member must not be null"));
    }

    private static void rule(String name, String bus, String... targetArns) {
        String busField = bus == null ? "" : ",\"EventBusName\":\"" + bus + "\"";
        call("PutRule", "{\"Name\":\"" + name + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"rnbt\\\"]}\""
                + busField + "}");
        StringBuilder targets = new StringBuilder();
        for (int i = 0; i < targetArns.length; i++) {
            targets.append(i == 0 ? "" : ",").append("{\"Id\":\"t").append(i).append("\",\"Arn\":\"")
                    .append(targetArns[i]).append("\"}");
        }
        call("PutTargets", "{\"Rule\":\"" + name + "\"" + busField + ",\"Targets\":[" + targets + "]}");
    }

    private static List<String> names(String body) {
        return list(body).getList("RuleNames");
    }

    private static JsonPath list(String body) {
        return given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static void call(String action, String body) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents." + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }
}
