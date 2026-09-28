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
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code oneOf}/{@code anyOf} with a scalar member — see
 * {@code src/test/resources/scalarunion.yaml}.
 *
 * <p>Such a union used to become an empty class named after the property
 * ({@code LocItem}, {@code IdOrName}), or an empty sealed interface for a
 * {@code oneOf}: it compiled, and then rejected every value the schema permits.
 * Kotlin generated {@code List<Any>} for the array case until the 4.0 type
 * model shared Java's answer. The honest type is {@code Object}/{@code Any},
 * as for the 3.1 spelling {@code type: [string, integer]}.
 *
 * <p>Checked by deserializing a real payload, not only by the text: an empty
 * class and {@code Object} both compile.
 */
class ScalarUnionTest {

    private static final Path SPEC = Path.of("src/test/resources/scalarunion.yaml");
    private static final String PAYLOAD = "{\"loc\": [\"body\", 0], \"msg\": \"bad\","
            + " \"id_or_name\": 42, \"either\": true, \"cat_or_name\": \"Tom\","
            + " \"code_or_name\": {\"code\": 7}}";

    @TempDir
    private Path result;

    private static GeneratorParams params() {
        return GeneratorParams.rootPackage("com.example");
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void javaMapsScalarUnionsToObject(JavaDtoStyle style) throws Exception {
        new JavaCodegen(params().javaDtoStyle(style)).generate(SPEC, result);
        String dto = Files.readString(result.resolve("com/example/dto/ValidationError.java"));

        assertThat(dto).containsPattern("List<Object> loc\\b");
        assertThat(dto).containsPattern("Object idOrName\\b");
        assertThat(dto).containsPattern("Object either\\b");
        assertThat(dto).containsPattern("Object catOrName\\b");
        assertThat(dto).containsPattern("Object codeOrName\\b");
        // A union of objects keeps its type.
        assertThat(dto).containsPattern("Pet pet\\b");
        assertNoInventedTypes(".java");
        assertThat(Files.readString(result.resolve("com/example/controller/Controller.java")))
                .contains("Object key");

        Path classes = GeneratedCodeCompiler.compileJava(result);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> type = loader.loadClass("com.example.dto.ValidationError");
            Object value = new ObjectMapper().readValue(PAYLOAD, type);
            String loc = style == JavaDtoStyle.RECORDS ? "loc" : "getLoc";
            assertThat(get(value, loc)).isEqualTo(List.of("body", 0));
            assertThat(get(value, style == JavaDtoStyle.RECORDS ? "idOrName" : "getIdOrName"))
                    .isEqualTo(42);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @Test
    void kotlinMapsScalarUnionsToAny() throws Exception {
        new KotlinCodegen(params()).generate(SPEC, result);
        String dto = Files.readString(result.resolve("com/example/dto/ValidationError.kt"));

        assertThat(dto).contains("loc: List<Any>,");
        assertThat(dto).contains("idOrName: Any? = null");
        assertThat(dto).contains("either: Any? = null");
        assertThat(dto).contains("catOrName: Any? = null");
        assertThat(dto).contains("codeOrName: Any? = null");
        assertThat(dto).contains("pet: Pet? = null");
        assertNoInventedTypes(".kt");
        assertThat(Files.readString(result.resolve("com/example/controller/Controller.kt")))
                .contains("key: Any?");

        Path classes = GeneratedCodeCompiler.compileKotlin(result);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> type = loader.loadClass("com.example.dto.ValidationError");
            ObjectMapper mapper = new ObjectMapper().registerModule(new KotlinModule.Builder().build());
            Object value = mapper.readValue(PAYLOAD, type);
            assertThat(get(value, "getLoc")).isEqualTo(List.of("body", 0));
            assertThat(get(value, "getIdOrName")).isEqualTo(42);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    private void assertNoInventedTypes(String extension) {
        for (String name : List.of("LocItem", "IdOrName", "Either", "CatOrName", "CodeOrName")) {
            assertThat(result.resolve("com/example/dto/" + name + extension)).doesNotExist();
        }
    }

    private static Object get(Object target, String accessor) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(accessor);
        return method.invoke(target);
    }
}
