package ru.curs.hurdygurdy;

public final class CaseUtils {
    private CaseUtils() {

    }

    public static String pathToCamel(String pathText) {
        if (pathText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pathText.length(); i++) {
            char c = pathText.charAt(i);
            switch (state) {
                case 0:
                    if (!(c == '/' || c == '{')) {
                        result.append(c);
                        state = 1;
                    }
                    break;
                case 1:
                    if (c == '/') {
                        state = 2;
                    } else if (c != '}') {
                        result.append(c);
                    }
                    break;
                case 2:
                    if (!(c == '/' || c == '{')) {
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    break;
            }
        }
        return result.toString();
    }

    public static String snakeToCamel(String snakeText) {
        return snakeToCamel(snakeText, false);
    }

    public static String snakeToCamel(String snakeText, boolean capitalizeFirst) {
        if (snakeText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < snakeText.length(); i++) {
            char c = snakeText.charAt(i);
            switch (state) {
                case 0:
                    if (capitalizeFirst) {
                        result.append(Character.toUpperCase(c));
                    } else {
                        result.append(c);
                    }
                    state = 1;
                    break;
                case 1:
                    result.append(c);
                    if (c != '_') {
                        state = 2;
                    }
                    break;
                case 2:
                    if (c == '_') {
                        state = 3;
                    } else {
                        result.append(c);
                    }
                    break;
                case 3:
                    if (c != '_') {
                        result.append(Character.toUpperCase(c));
                        state = 2;
                    }
                    break;
            }
        }
        return result.toString();
    }

    public static String kebabToCamel(String kebabPascalText) {
        if (kebabPascalText == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < kebabPascalText.length(); i++) {
            char c = kebabPascalText.charAt(i);
            switch (state) {
                case 0:
                    result.append(Character.toLowerCase(c));
                    state = 1;
                    break;
                case 1:
                    result.append(c);
                    if (c != '-') {
                        state = 2;
                    }
                    break;
                case 2:
                    if (c == '-') {
                        state = 3;
                    } else {
                        result.append(c);
                    }
                    break;
                case 3:
                    if (c != '-') {
                        result.append(Character.toUpperCase(c));
                        state = 2;
                    }
                    break;
            }
        }
        return result.toString();
    }

    /**
     * Strips characters that are illegal in a Kotlin identifier even when it is
     * back-ticked (for example the {@code []} in a multipart field named
     * {@code files[0]}). The original spec name is still emitted verbatim in the
     * {@code name = "..."} of the binding annotation, so only the generated
     * parameter/property identifier is affected. A leading digit left after
     * stripping is prefixed with {@code _}; an empty result becomes {@code _}.
     *
     * <p>For names that are already legal (the common case) this is a no-op.
     *
     * @param name the raw spec name to turn into a legal Kotlin identifier
     */
    public static String toIdentifier(String name) {
        if (name == null) {
            return null;
        }
        // Forbidden in a back-ticked Kotlin identifier: . ; [ ] / < > : \ ` and
        // whitespace/control characters.
        String cleaned = name.replaceAll("[\\[\\]./<>:;\\\\`\\s\\p{Cntrl}]", "");
        if (cleaned.isEmpty()) {
            return "_";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            return "_" + cleaned;
        }
        return cleaned;
    }

    /**
     * Produces a valid Java class name from a string.
     * @param text any string to be converted to a clean CamelCase
     */
    public static String normalizeToCamel(String text) {
        if (text == null) {
            return null;
        }
        int state = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (state) {
                case 0: // ─── need first identifier character ──────────────────────
                    if (Character.isJavaIdentifierStart(c)) {
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    } else if (Character.isJavaIdentifierPart(c)) {
                        // still not a legal start (e.g. digit) → prefix “_”
                        result.append('_');
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    // everything else is a delimiter; stay in state 0
                    break;

                case 1: // ─── normal copy ──────────────────────────────────────────
                    if (Character.isJavaIdentifierPart(c)) {
                        result.append(c);
                    } else {
                        state = 2; // saw delimiter → capitalize next legal char
                    }
                    break;

                case 2: // ─── after delimiter ──────────────────────────────────────
                    if (Character.isJavaIdentifierPart(c)) {
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    // another delimiter ⇒ remain in state 2
                    break;
            }
        }

        // Guard: empty input or only delimiters
        if (result.length() == 0) {
            return "__";
        }

        return result.toString();
    }

    public static String normalizeToScreamingSnake(String text) {
        if (text == null) {
            return null;
        }

        int state = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (state) {
                case 0: // ─── first legal char ───────────────────────────────────
                    if (Character.isJavaIdentifierStart(c)) {
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    } else if (Character.isJavaIdentifierPart(c)) {
                        result.append('_');
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    // other chars are delimiters; stay in state 0
                    break;

                case 1: // ─── inside token ───────────────────────────────────────
                    if (Character.isJavaIdentifierPart(c)) {
                        result.append(Character.toUpperCase(c));
                    } else {
                        state = 2;              // saw delimiter
                    }
                    break;

                case 2: // ─── after delimiter ────────────────────────────────────
                    if (Character.isJavaIdentifierPart(c)) {
                        result.append('_');
                        result.append(Character.toUpperCase(c));
                        state = 1;
                    }
                    // additional delimiters collapse; remain in state 2
                    break;
            }
        }

        // Guard: no legal characters at all
        if (result.length() == 0) {
            return "__";
        }
        return result.toString();
    }
}
