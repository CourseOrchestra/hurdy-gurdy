package ru.curs.hurdygurdy;

import java.util.Locale;

/**
 * Whether the generator emits a server resource interface (to be implemented)
 * or a client interface (whose implementation the framework provides).
 */
public enum Role {
    /** Server resource interface (to be implemented by the application). */
    SERVER,
    /** Client interface (whose implementation the framework provides). */
    CLIENT;

    /**
     * Parses a role name case-insensitively, defaulting to {@link #SERVER} for a
     * {@code null} or blank value.
     *
     * @param value role name (e.g. {@code "server"} or {@code "client"})
     * @return the matching role, or {@link #SERVER} when unspecified
     */
    public static Role of(String value) {
        if (value == null || value.isBlank()) {
            return SERVER;
        }
        return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
