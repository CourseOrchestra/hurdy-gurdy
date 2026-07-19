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
