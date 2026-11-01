package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SidecarSchemaCompiler#withDirectivesAndScalars} prepends AppSync's directives and its 17
 * scalars to the customer's SDL. AppSync takes a schema that declares one of those scalars itself
 * ({@code scalar AWSDateTime}), so the preparation leaves out each one the SDL already declares,
 * and the sidecar sees one definition of it.
 */
class SidecarSchemaCompilerTest {

    /** AppSync's 17 scalars, in the order the compiler injects them. */
    private static final List<String> ALL_SCALARS = List.of(
            "AWSJSON", "AWSDateTime", "AWSDate", "AWSTime", "AWSTimestamp", "AWSEmail", "AWSURL",
            "AWSPhone", "AWSIPAddress", "AWSBoolean", "AWSLong", "AWSInteger", "AWSShort", "AWSFloat",
            "AWSBigDecimal", "AWSBigInt", "AWSByte");

    private static final Pattern SCALAR_LINE = Pattern.compile("(?m)^scalar (\\w+)$");

    private final SidecarSchemaCompiler compiler = new SidecarSchemaCompiler(null);

    @Test
    void aDeclaredBuiltInScalarIsNotInjectedAgain() {
        String sdl = """
                \"""An ISO 8601 date and time.\"""
                scalar AWSDateTime

                type Query { now: AWSDateTime, data: AWSJSON }
                """;

        String prepared = compiler.withDirectivesAndScalars(sdl);

        assertTrue(prepared.endsWith(sdl), "the customer's SDL is sent as written");
        assertEquals(1, count(prepared, "AWSDateTime"), "one scalar AWSDateTime, the customer's");
        List<String> others = new ArrayList<>(ALL_SCALARS);
        others.remove("AWSDateTime");
        assertEquals(others, injectedScalars(prepared, sdl));
    }

    @Test
    void anSdlDeclaringNoneIsPreparedAsBefore() {
        String sdl = "type Query { now: AWSDateTime, data: AWSJSON }";

        StringBuilder expected = new StringBuilder();
        for (AppSyncDirective directive : AppSyncDirective.values()) {
            expected.append(directive.sdl()).append('\n');
        }
        expected.append('\n');
        for (String scalarName : ALL_SCALARS) {
            expected.append("scalar ").append(scalarName).append('\n');
        }
        expected.append('\n').append(sdl);

        assertEquals(expected.toString(), compiler.withDirectivesAndScalars(sdl));
    }

    @Test
    void aScalarInADescriptionOrACommentIsNoDeclaration() {
        String sdl = """
                \"""
                scalar AWSDateTime
                is AppSync's own, never this schema's.
                \"""
                type Query {
                  "scalar AWSDateTime, as AppSync defines it"
                  now: AWSDateTime
                  # scalar AWSDateTime
                  data: AWSJSON
                }

                \"""A JSON document.\"""
                scalar AWSJSON
                """;

        String prepared = compiler.withDirectivesAndScalars(sdl);

        List<String> others = new ArrayList<>(ALL_SCALARS);
        others.remove("AWSJSON");
        assertEquals(others, injectedScalars(prepared, sdl));
    }

    @Test
    void anExtensionOfABuiltInScalarIsNoDeclaration() {
        String sdl = """
                extend scalar AWSDateTime @aws_api_key

                type Query { now: AWSDateTime }
                """;

        assertEquals(ALL_SCALARS, injectedScalars(compiler.withDirectivesAndScalars(sdl), sdl));
    }

    /** The scalars the compiler prepended, read from the text before the customer's SDL. */
    private static List<String> injectedScalars(String prepared, String sdl) {
        assertTrue(prepared.endsWith(sdl), "the customer's SDL is sent as written");
        Matcher matcher = SCALAR_LINE.matcher(prepared.substring(0, prepared.length() - sdl.length()));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int count(String prepared, String scalarName) {
        Matcher matcher = SCALAR_LINE.matcher(prepared);
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(1).equals(scalarName)) {
                count++;
            }
        }
        return count;
    }
}
