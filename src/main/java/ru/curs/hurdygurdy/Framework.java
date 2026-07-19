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
