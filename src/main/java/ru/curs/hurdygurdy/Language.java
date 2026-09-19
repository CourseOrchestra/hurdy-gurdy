/*
 * Copyright 2026 Ivan Ponomarev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.curs.hurdygurdy;

import java.util.Locale;

/**
 * The language the generated sources are written in.
 *
 * <p>The last of the four axes of variation to get a type. It was a
 * {@code String} compared with {@code "java".equalsIgnoreCase(...)} in each of
 * the three front ends, which meant a misspelt language silently generated the
 * other one; and the Gradle plugin, needing something typed for its DSL, had
 * declared an enum of its own.
 *
 * @see Codegen#of(Language, GeneratorParams)
 */
public enum Language {
    /** Java. */
    JAVA,
    /** Kotlin. */
    KOTLIN;

    /**
     * Parses a language name case-insensitively, defaulting to {@link #JAVA}
     * for a {@code null} or blank value.
     *
     * @param value language name (e.g. {@code "java"} or {@code "kotlin"})
     * @return the matching language, or {@link #JAVA} when unspecified
     * @throws IllegalArgumentException if the value names no language
     */
    public static Language of(String value) {
        if (value == null || value.isBlank()) {
            return JAVA;
        }
        return Language.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
