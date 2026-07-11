package ru.curs.hurdygurdy;

import java.util.Locale;

/**
 * Shape of the Java DTO classes the generator emits. Applies to Java output
 * only; Kotlin always uses data classes.
 */
public enum JavaDtoStyle {
    /** Lombok {@code @Data} classes (default; historical behaviour). */
    LOMBOK,
    /** Plain classes with explicit getters/setters, equals, hashCode, toString. */
    POJO,
    /** Java records; sealed interfaces model oneOf/discriminator polymorphism. */
    RECORDS;

    /**
     * Parses a style name case-insensitively, defaulting to {@link #LOMBOK} for
     * a {@code null} or blank value.
     *
     * @param value style name (e.g. {@code "lombok"}, {@code "pojo"}, {@code "records"})
     * @return the matching style, or {@link #LOMBOK} when unspecified
     */
    public static JavaDtoStyle of(String value) {
        if (value == null || value.isBlank()) {
            return LOMBOK;
        }
        return JavaDtoStyle.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
