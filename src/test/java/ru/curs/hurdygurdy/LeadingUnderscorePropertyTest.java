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
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reproducer for <a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/566">issue
 * 566</a>: a property name with a leading underscore (e.g. {@code _anchors}) is
 * valid {@code snake_case} and must not be rejected by
 * {@code forceSnakeCaseForProperties}.
 *
 * <p>Three things are asserted, for every Java DTO style and for Kotlin:
 * <ol>
 *   <li>generation succeeds at all (before the fix it threw
 *       {@code Property '_anchors' of schema 'UserConfig' is not in snake case});</li>
 *   <li>the leading underscore is <em>kept</em> in the generated
 *       Java/Kotlin property identifier — the user asked for a leading
 *       underscore, and keeping it also avoids clashing with a same-named
 *       underscore-less property;</li>
 *   <li>the JSON wire name keeps the leading underscore too. This is the part
 *       the regex fix alone does <em>not</em> buy: Jackson's
 *       {@code SnakeCaseStrategy} silently drops the first leading underscore,
 *       so {@code _anchors} would be read/written as {@code anchors} and
 *       {@code __meta} as {@code _meta} unless the generator pins the name with
 *       an explicit {@code @JsonProperty}.</li>
 * </ol>
 */
class LeadingUnderscorePropertyTest {

    private static final String SPEC = "src/test/resources/issue566.yaml";
    private static final String DTO_PKG = "com.example.dto";

    /**
     * The wire shape the spec asks for: every leading underscore intact. Used
     * both as the deserialization input and as the expected re-serialization
     * output, so a mangled name fails whichever direction it is mangled in.
     */
    private static final String WIRE_JSON = """
            {"id":"i","_anchors":{"name":"n"},"_created_at":"then",\
            "__meta":"m","plain_name":"p"}""";

    @TempDir
    private Path generated;

    @ParameterizedTest(name = "{0}")
    @EnumSource(JavaDtoStyle.class)
    void javaKeepsLeadingUnderscore(JavaDtoStyle style) throws Exception {
        assertThatCode(() -> new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .javaDtoStyle(style)
                .forceSnakeCaseForProperties(true))
                .generate(Path.of(SPEC), generated))
                .as("leading-underscore property accepted in snake-case mode [%s]", style)
                .doesNotThrowAnyException();

        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> userConfig = loader.loadClass(DTO_PKG + ".UserConfig");
            assertThat(propertyNames(userConfig))
                    .as("generated Java property identifiers [%s]", style)
                    .contains("_anchors", "_createdAt", "__meta", "plainName");
            assertWireNamesPreserved(new ObjectMapper(), userConfig, style.toString());
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @Test
    void kotlinKeepsLeadingUnderscore() throws Exception {
        assertThatCode(() -> new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(true))
                .generate(Path.of(SPEC), generated))
                .as("leading-underscore property accepted in snake-case mode [Kotlin]")
                .doesNotThrowAnyException();

        Path classes = GeneratedCodeCompiler.compileKotlin(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> userConfig = loader.loadClass(DTO_PKG + ".UserConfig");
            assertThat(propertyNames(userConfig))
                    .as("generated Kotlin property identifiers")
                    .contains("_anchors", "_createdAt", "__meta", "plainName");
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new KotlinModule.Builder().build());
            mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));
            assertWireNamesPreserved(mapper, userConfig, "Kotlin");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * The spec's property names must survive a full
     * deserialize&nbsp;&rarr;&nbsp;re-serialize hop unchanged: reading
     * {@link #WIRE_JSON} must populate every property (so the read side maps the
     * underscored keys) and writing it back must reproduce exactly the same tree
     * (so the write side does too).
     */
    private static void assertWireNamesPreserved(ObjectMapper mapper, Class<?> clazz, String context)
            throws Exception {
        Object back = mapper.readValue(WIRE_JSON, clazz);
        String json = mapper.writeValueAsString(back);
        assertThat(mapper.readTree(json))
                .as("leading underscores kept on the wire [%s]: %s", context, json)
                .isEqualTo(mapper.readTree(WIRE_JSON));
    }

    /**
     * The declared property identifiers of a generated DTO: record components
     * for a Java record, otherwise the instance fields (which is also what a
     * Kotlin {@code data class} property compiles down to).
     */
    private static List<String> propertyNames(Class<?> clazz) {
        List<String> names = new ArrayList<>();
        if (clazz.isRecord()) {
            for (RecordComponent c : clazz.getRecordComponents()) {
                names.add(c.getName());
            }
            return names;
        }
        for (Field f : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                names.add(f.getName());
            }
        }
        return names;
    }
}
