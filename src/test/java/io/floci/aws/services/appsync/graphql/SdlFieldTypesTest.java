package io.floci.aws.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The field types the data plane reads out of an API's SDL text. */
class SdlFieldTypesTest {

    private static final String SDL = """
            # the API
            \"\"\"
            Queries { settings: Int } is in a description, not a type
            \"\"\"
            type Query @aws_api_key {
              "a field's description: AWSJSON is not its type"
              settings(installId: ID!, deep: Boolean = false): AWSJSON!
              tags: [AWSJSON]
              name: String @aws_auth(cognito_groups: ["admins"])
              count: Int
            }
            type Install { settings: String }
            extend type Query { later: [ AWSJSON ! ] ! }
            interface Node { payload: AWSJSON }
            input Handshake { manifest: AWSJSON!, name: String }
            type Mutation {
              publish(image: AWSJSON, handshake: Handshake, names: [String], images: [AWSJSON!]!): Boolean
            }
            """;

    @Test
    void aFieldsTypeIsReadPastItsArgumentsAndWithoutItsWrappers() {
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", false)),
                SdlFieldTypes.declaredType(SDL, "Query", "settings"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", true)),
                SdlFieldTypes.declaredType(SDL, "Query", "tags"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("String", false)),
                SdlFieldTypes.declaredType(SDL, "Query", "name"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("Int", false)),
                SdlFieldTypes.declaredType(SDL, "Query", "count"));
    }

    @Test
    void aTypesExtensionAndAnInterfaceAreReadAndAnotherTypesFieldOfTheSameNameIsNot() {
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", true)),
                SdlFieldTypes.declaredType(SDL, "Query", "later"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", false)),
                SdlFieldTypes.declaredType(SDL, "Node", "payload"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("String", false)),
                SdlFieldTypes.declaredType(SDL, "Install", "settings"));
    }

    @Test
    void anArgumentsTypeIsReadFromItsFieldAndAnInputTypesFieldsFromItsDeclaration() {
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", false)),
                SdlFieldTypes.argumentType(SDL, "Mutation", "publish", "image"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("Handshake", false)),
                SdlFieldTypes.argumentType(SDL, "Mutation", "publish", "handshake"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", true)),
                SdlFieldTypes.argumentType(SDL, "Mutation", "publish", "images"));
        assertEquals(Optional.empty(), SdlFieldTypes.argumentType(SDL, "Mutation", "publish", "other"));
        assertEquals(Optional.empty(), SdlFieldTypes.argumentType(SDL, "Query", "tags", "image"));
        assertEquals(Optional.of(new SdlFieldTypes.FieldType("AWSJSON", false)),
                SdlFieldTypes.declaredType(SDL, "Handshake", "manifest"));
        assertTrue(SdlFieldTypes.isInputType(SDL, "Handshake"));
        assertFalse(SdlFieldTypes.isInputType(SDL, "Install"));
    }

    @Test
    void aFieldOrATypeTheSdlDoesNotDeclareIsEmpty() {
        assertEquals(Optional.empty(), SdlFieldTypes.declaredType(SDL, "Query", "missing"));
        assertEquals(Optional.empty(), SdlFieldTypes.declaredType(SDL, "Queries", "settings"));
        assertEquals(Optional.empty(), SdlFieldTypes.declaredType(null, "Query", "settings"));
    }
}
