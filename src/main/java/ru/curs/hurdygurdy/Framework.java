package ru.curs.hurdygurdy;

/**
 * Target web framework whose annotations the generator emits on controller
 * interfaces.
 */
public enum Framework {
    /** Spring Web framework. */
    SPRING,
    /** Quarkus Web framework. */
    QUARKUS;

    /**
     * Parses a framework name case-insensitively, defaulting to {@link #SPRING}
     * for a {@code null} or blank value.
     *
     * @param value framework name (e.g. {@code "spring"} or {@code "quarkus"})
     * @return the matching framework, or {@link #SPRING} when unspecified
     */
    public static Framework of(String value) {
        if (value == null || value.isBlank()) {
            return SPRING;
        }
        return Framework.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
