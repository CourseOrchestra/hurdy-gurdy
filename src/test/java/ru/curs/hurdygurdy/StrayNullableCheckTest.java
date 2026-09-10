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

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code nullable} keyword a 3.0 &rarr; 3.1 version bump leaves behind is
 * ignored (OpenAPI 3.1 removed it), but never in silence.
 */
class StrayNullableCheckTest {

    private static final String SPEC_31 = "src/test/resources/straynullable31.yaml";
    private static final String SPEC_30 = "src/test/resources/recordsnullable.yaml";

    @TempDir
    private Path result;

    @Test
    void reportsEveryStrayNullableIn31Document() throws IOException {
        assertThat(locationsIn(SPEC_31)).containsExactlyInAnyOrder(
                "#/components/schemas/Thing",
                "#/components/schemas/Thing/properties/req_nullable",
                "#/paths//api/v1/thing/get/parameters/filter",
                // A response header carries a schema of its own...
                "#/paths//api/v1/thing/get/responses/200/headers/X-Total",
                // ...and a callback is a whole path item, operations included.
                "#/paths//api/v1/thing/get/callbacks/thing_changed/{$request.query.callback_url}"
                        + "/post/requestBody/content/application/json/properties/changed_at");
    }

    @Test
    void reportsNothingIn30DocumentWhereNullableIsAKeyword() throws IOException {
        // recordsnullable.yaml is full of `nullable: true` — legitimately, since
        // it is a 3.0 document, where the parser reads it into the schema itself.
        assertThat(locationsIn(SPEC_30)).isEmpty();
    }

    @Test
    void generationWarnsThroughTheListener() throws IOException {
        List<String> warnings = new ArrayList<>();
        Codegen<?> codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example"));
        codegen.setWarningListener(warnings::add);

        codegen.generate(Path.of(SPEC_31), result);

        assertThat(warnings).hasSize(5);
        assertThat(warnings).allMatch(w -> w.contains("not an OpenAPI 3.1 keyword"));
        assertThat(warnings).anyMatch(w -> w.contains("#/components/schemas/Thing/properties/req_nullable"));
    }

    private static List<String> locationsIn(String spec) throws IOException {
        OpenAPI openAPI = new OpenAPIParser()
                .readContents(Files.readString(Path.of(spec)), null, new ParseOptions())
                .getOpenAPI();
        return StrayNullableCheck.locations(openAPI);
    }
}
