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

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * A kind of interface the generator can emit for the API paths. Any subset of
 * roles can be generated in a single run; each role gets its own name suffix
 * ({@code XxxController}, {@code XxxApi}, {@code XxxClient}), all in the
 * {@code controller} subpackage.
 */
public enum Role {
    /** Server interface, to be implemented by the application. */
    CONTROLLER("Controller"),
    /**
     * Pure contract interface: same framework annotations as the controller,
     * but without response-related artifacts ({@code HttpServletResponse}
     * parameter, {@code Response} return type). Suitable as a typed contract
     * for OpenFeign (Spring) or MicroProfile {@code RestClientBuilder}
     * (Quarkus), or for hand-written client/test implementations.
     */
    API("Api"),
    /** Client interface whose implementation the framework provides. */
    CLIENT("Client");

    private final String suffix;

    Role(String suffix) {
        this.suffix = suffix;
    }

    /**
     * Name suffix appended to the OpenAPI tag to form the interface name.
     *
     * @return the suffix, e.g. {@code "Controller"}
     */
    public String getSuffix() {
        return suffix;
    }

    /**
     * Parses a comma-separated list of role names case-insensitively,
     * defaulting to {@link #CONTROLLER} for a {@code null} or blank value.
     *
     * @param value role names, e.g. {@code "controller,client"}
     * @return the matching roles, or {@code {CONTROLLER}} when unspecified
     */
    public static Set<Role> parse(String value) {
        EnumSet<Role> result = EnumSet.noneOf(Role.class);
        if (value != null) {
            for (String name : value.split(",")) {
                if (!name.isBlank()) {
                    result.add(Role.valueOf(name.trim().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return result.isEmpty() ? EnumSet.of(CONTROLLER) : result;
    }
}
