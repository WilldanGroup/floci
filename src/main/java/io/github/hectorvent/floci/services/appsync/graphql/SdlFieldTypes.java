package io.github.hectorvent.floci.services.appsync.graphql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a field's declared type out of an API's SDL text, for what the data plane must know about a field
 * that the sidecar does not tell it: the sidecar coerces scalars itself and its resolver callback names a
 * field by its coordinates alone. Floci holds the SDL as text and compiles nothing, so this is a scan of
 * the text: the body of every {@code type}, {@code interface} or {@code input} declaration, or extension, of
 * the type, and the field's type after its name and its arguments, or an argument's type inside them.
 * Comments and descriptions are dropped first.
 */
public final class SdlFieldTypes {

    /** The named type a field is declared with, and whether it is declared as a list of it. */
    public record FieldType(String name, boolean list) {
    }

    private static final Pattern BLOCK_DESCRIPTION = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"");
    private static final Pattern DESCRIPTION = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern ARGUMENTS = Pattern.compile("\\([^)]*\\)");

    private SdlFieldTypes() {
    }

    /** A type is a name, each wrapper a list bracket or a non-null mark: {@code [ AWSJSON ! ] !}. */
    private static final String TYPE = "((?:\\[\\s*)*\\w+(?:\\s*!)?(?:\\s*]\\s*!?)*)";

    /**
     * The field's declared type on the object, interface or input type, or empty when the SDL declares none.
     */
    public static Optional<FieldType> declaredType(String sdl, String typeName, String fieldName) {
        if (sdl == null || typeName == null || fieldName == null) {
            return Optional.empty();
        }
        Pattern field = Pattern.compile("(?<![\\w])" + Pattern.quote(fieldName) + "\\s*(?:\\(\\))?\\s*:\\s*" + TYPE);
        for (String body : bodies(sdl, typeName)) {
            // Arguments go first, so an argument named like a field is not read as one.
            Matcher found = field.matcher(ARGUMENTS.matcher(body).replaceAll("()"));
            if (found.find()) {
                return Optional.of(fieldType(found.group(1)));
            }
        }
        return Optional.empty();
    }

    /** The declared type of the field's argument, or empty when the field declares no such argument. */
    public static Optional<FieldType> argumentType(String sdl, String typeName, String fieldName, String argument) {
        if (sdl == null || typeName == null || fieldName == null || argument == null) {
            return Optional.empty();
        }
        Pattern arguments = Pattern.compile("(?<![\\w])" + Pattern.quote(fieldName) + "\\s*\\(([^)]*)\\)\\s*:");
        Pattern declared = Pattern.compile("(?<![\\w])" + Pattern.quote(argument) + "\\s*:\\s*" + TYPE);
        for (String body : bodies(sdl, typeName)) {
            Matcher found = arguments.matcher(body);
            while (found.find()) {
                Matcher type = declared.matcher(found.group(1));
                if (type.find()) {
                    return Optional.of(fieldType(type.group(1)));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the SDL declares the name as an input object type. */
    public static boolean isInputType(String sdl, String typeName) {
        return sdl != null && typeName != null
                && Pattern.compile("\\binput\\s+" + Pattern.quote(typeName) + "\\b").matcher(withoutText(sdl)).find();
    }

    /** The bodies of the type's declaration and its extensions, in order. */
    private static List<String> bodies(String sdl, String typeName) {
        Pattern block = Pattern.compile("\\b(?:extend\\s+)?(?:type|interface|input)\\s+" + Pattern.quote(typeName)
                + "\\b[^{]*\\{([^}]*)}");
        List<String> bodies = new ArrayList<>();
        Matcher blocks = block.matcher(withoutText(sdl));
        while (blocks.find()) {
            bodies.add(blocks.group(1));
        }
        return bodies;
    }

    private static String withoutText(String sdl) {
        return COMMENT.matcher(DESCRIPTION.matcher(BLOCK_DESCRIPTION.matcher(sdl).replaceAll(" ")).replaceAll(" "))
                .replaceAll(" ");
    }

    private static FieldType fieldType(String declared) {
        String compact = declared.replaceAll("\\s+", "");
        return new FieldType(compact.replaceAll("[\\[\\]!]", ""), compact.startsWith("["));
    }
}
