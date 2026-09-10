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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Functional test for the OpenAPI 3.1 {@code type: [X, "null"]} spelling of a
 * nullable property (<a
 * href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/603">issue
 * 603</a>), in {@code records} DTO style.
 *
 * <p>The snapshots check the <em>text</em> of the generated types; this checks
 * the behaviour that text is there for. A property that is {@code required}
 * with {@code type: [string, "null"]} must be present but may be {@code null},
 * so {@code {"plain":"x","opt_string":null}} is a valid payload — before the
 * fix the type array was not recognised at all, the property counted as
 * non-nullable, and the compact constructor's {@code Objects.requireNonNull}
 * rejected the (valid) explicit null.
 */
class TypeArray31Test {

    private static final String SPEC = "src/test/resources/typearray31.yaml";

    @TempDir
    private Path generated;

    @Test
    void requiredTypeArrayPropertyAcceptsExplicitNull() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of(SPEC), generated);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> sample = loader.loadClass("com.example.dto.Sample");
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

            // The type array is read as a real type, not as Object/Any.
            assertThat(sample.getMethod("optString").getReturnType()).isEqualTo(String.class);
            assertThat(sample.getMethod("optLong").getReturnType()).isEqualTo(Long.class);
            assertThat(sample.getMethod("optList").getReturnType()).isEqualTo(java.util.List.class);

            // required + `type: [string, "null"]`, present with an explicit
            // null: valid, and the non-null `plain` still arrives. The two
            // required properties that are NOT nullable (`plain` and the
            // non-null list `null_items`) have to be there.
            String payload = "{\"plain\":\"x\",\"null_items\":[],"
                    + "\"opt_string\":null,\"opt_long\":null,\"opt_list\":null}";
            Object value = mapper.readValue(payload, sample);
            assertThat(sample.getMethod("optString").invoke(value)).isNull();
            assertThat(sample.getMethod("optLong").invoke(value)).isNull();
            assertThat(sample.getMethod("optList").invoke(value)).isNull();
            assertThat(sample.getMethod("plain").invoke(value)).isEqualTo("x");

            // required and NOT nullable: still enforced.
            assertThatThrownBy(() -> mapper.readValue("{\"null_items\":[],\"opt_string\":\"y\"}", sample))
                    .hasMessageContaining("plain");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }
}
