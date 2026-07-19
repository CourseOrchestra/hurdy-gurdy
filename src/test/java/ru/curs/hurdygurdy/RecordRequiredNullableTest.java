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
 * Functional test for a property that is both {@code required} and
 * {@code nullable: true} in {@code records} DTO style.
 *
 * <p>Per OpenAPI 3.0 such a property must be present but its value may be
 * {@code null}, so {@code {"foo":"x","baz":null}} is a valid payload. The
 * snapshot only checks the <em>text</em> of the compact constructor; here we
 * generate the record, compile it, load it and run it through a Jackson
 * {@link ObjectMapper} to prove the valid explicit {@code null} deserializes —
 * while a required, non-nullable property still rejects a missing value.
 */
class RecordRequiredNullableTest {

    private static final String SPEC = "src/test/resources/recordsnullable.yaml";

    @TempDir
    private Path generated;

    @Test
    void requiredNullablePropertyAcceptsExplicitNull() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of(SPEC), generated);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> foo = loader.loadClass("com.example.dto.Foo");
            ObjectMapper mapper = new ObjectMapper();

            // required + nullable `baz` present with an explicit null: valid.
            Object value = mapper.readValue("{\"foo\":\"x\",\"baz\":null}", foo);
            assertThat(value).isNotNull();
            assertThat(foo.getMethod("baz").invoke(value)).isNull();
            assertThat(foo.getMethod("foo").invoke(value)).isEqualTo("x");

            // required + NOT nullable `foo` missing: still rejected.
            assertThatThrownBy(() -> mapper.readValue("{\"baz\":\"y\"}", foo))
                    .hasMessageContaining("foo");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }
}
