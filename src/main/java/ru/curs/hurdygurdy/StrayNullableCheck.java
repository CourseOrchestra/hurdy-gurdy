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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds {@code nullable} keywords left over in an OpenAPI 3.1 document.
 *
 * <p>OpenAPI 3.1 removed {@code nullable} outright (JSON Schema 2020-12 has no
 * such keyword) in favour of {@code type: [X, "null"]}, so a 3.1 document that
 * still carries it says nothing at all: swagger-parser leaves
 * {@code Schema.getNullable()} null and drops the keyword into the schema's
 * extension map together with every other unrecognised keyword. hurdy-gurdy
 * follows the spec and ignores it — but ignoring it <em>silently</em> is the
 * documented trap of a 3.0 &rarr; 3.1 migration (bump the version string, leave
 * the {@code nullable}s in place, and every nullable field quietly turns
 * non-null), so each occurrence is reported as a warning instead.
 */
final class StrayNullableCheck {

    private StrayNullableCheck() {
    }

    /**
     * The locations of every stray {@code nullable} in a 3.1 document, as
     * JSON-pointer-like paths (e.g.
     * {@code #/components/schemas/Sample/properties/opt}), in document order.
     * Always empty for a 3.0 document, where {@code nullable} is a legitimate
     * keyword that the parser reads into the schema itself.
     *
     * @param openAPI the parsed document
     * @return the stray {@code nullable} locations
     */
    static List<String> locations(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getSpecVersion() != SpecVersion.V31) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        SchemaWalker.walk(openAPI, (schema, path) -> {
            if (schema.getExtensions() != null && schema.getExtensions().containsKey("nullable")) {
                found.add(path);
            }
        });
        return found;
    }
}
