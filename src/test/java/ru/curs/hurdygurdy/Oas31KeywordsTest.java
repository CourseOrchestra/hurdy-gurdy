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

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JSON Schema 2020-12 keywords an OpenAPI 3.1 document may use that a 3.0
 * document cannot express — see {@code src/test/resources/oas31keywords.yaml}
 * for the specification and <a
 * href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/615">issue
 * 615</a> for the report.
 *
 * <p>Three of these used to abort generation with a {@code NullPointerException}
 * and the rest produced types that were quietly meaningless — in Java, an empty
 * nested class invented from the property name, which compiles and means
 * nothing. Both failure modes are worse than an honest {@code Object}, which is
 * what a schema that genuinely admits any value maps to now.
 *
 * <p>Assertions are on individual declarations rather than on a snapshot of the
 * whole file: each line here is a separate decision about a separate keyword,
 * and a snapshot would let one of them regress behind another's churn.
 */
class Oas31KeywordsTest {

    private static final Path SPEC = Path.of("src/test/resources/oas31keywords.yaml");

    @TempDir
    private Path result;

    private static GeneratorParams params() {
        return GeneratorParams.rootPackage("com.example");
    }

    @Test
    void javaMapsEvery31Keyword() throws IOException {
        new JavaCodegen(params()).generate(SPEC, result);
        String keywords = Files.readString(result.resolve("com/example/dto/Keywords.java"));

        // An array with no item schema, and a tuple: neither has a single item
        // type, so both widen to a list of anything.
        assertThat(keywords).contains("private List<Object> looseArray;");
        assertThat(keywords).contains("private List<Object> tuple;");
        // `items` alongside `prefixItems` constrains only the elements after the
        // prefix, so it is not the common element type: List<Boolean> here would
        // claim the first two elements are booleans when they are string/integer.
        assertThat(keywords).contains("private List<Object> tupleWithItems;");

        // `const` carries its own JSON type.
        assertThat(keywords).contains("private String constString;");
        assertThat(keywords).contains("private Integer constInt;");
        assertThat(keywords).contains("private boolean constBool;");

        // No single type: Object, NOT an invented empty class.
        assertThat(keywords).contains("private Object multiType;");
        assertThat(keywords).contains("private Object boolSchema;");
        assertThat(keywords).doesNotContain("class MultiType");
        assertThat(keywords).doesNotContain("class BoolSchema");

        // contentEncoding is what makes a string carry bytes.
        assertThat(keywords).contains("private byte[] octetsEncoded;");
        assertThat(keywords).contains("private byte[] octetsEncodingOnly;");
        assertThat(keywords).contains("private byte[] octetsLegacy;");
        // contentMediaType alone describes the DECODED content; the JSON value is
        // still a plain string, and typing it as bytes would base64 it on the wire.
        assertThat(keywords).contains("private String octetsMediaTypeOnly;");

        // The enum's null is nullability, not a constant.
        assertThat(keywords).contains("public enum Colour");
        assertThat(keywords).contains("RED");
        assertThat(keywords).contains("GREEN");
        assertThat(keywords).doesNotContain("NULL");
        assertThat(keywords).contains("public enum Shade");

        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    /**
     * An enum whose {@code null} member is the <em>only</em> statement of its
     * nullability — there is no {@code type: [..., "null"]} to carry it.
     *
     * <p>The {@code null} has to come out of the member list (it is not a
     * constant, and naming an enum entry after it throws), but taking it out
     * must not take the nullability with it. swagger-parser infers
     * {@code types: [string]} for such a schema, so nothing else records the
     * fact; the normalizer therefore adds {@code "null"} to the type set, which
     * is the canonical 3.1 spelling of exactly what the member said.
     *
     * <p>Checked behaviourally rather than on the text: in {@code records} style
     * a required non-nullable component is guarded by
     * {@code Objects.requireNonNull}, so a required property that still accepts
     * an explicit null is proof the nullability survived.
     */
    @Test
    void typelessNullableEnumKeepsItsNullability() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(SPEC, result);
        Path classes = GeneratedCodeCompiler.compileJava(result);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> type = loader.loadClass("com.example.dto.RequiredNullableEnum");
            ObjectMapper mapper = new ObjectMapper();

            // required, present, explicitly null: the enum said null was allowed.
            Object value = mapper.readValue("{\"shade\":null}", type);
            assertThat(type.getMethod("shade").invoke(value)).isNull();

            // and a real member still round-trips.
            Object red = mapper.readValue("{\"shade\":\"red\"}", type);
            assertThat(type.getMethod("shade").invoke(red)).hasToString("RED");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @Test
    void javaMapsFreeFormDictionariesToObject() throws IOException {
        new JavaCodegen(params()).generate(SPEC, result);

        // `additionalProperties: true` and `additionalProperties: {}` are the two
        // spellings of a free-form dictionary and must agree. `true` used to fall
        // through to Map<String, String>, which is not merely imprecise but wrong:
        // it cannot hold the values the schema permits.
        assertThat(Files.readString(result.resolve("com/example/dto/FreeDictTrue.java")))
                .contains("private Map<String, Object> additionalProperties");
        assertThat(Files.readString(result.resolve("com/example/dto/FreeDictEmpty.java")))
                .contains("private Map<String, Object> additionalProperties");
    }

    /**
     * All three spellings of {@code additionalProperties}, at both versions.
     *
     * <p>These disagreed three ways. swagger-parser hands {@code true}/{@code false}
     * over as a {@link Boolean} for a 3.0 document but as a schema object for a 3.1
     * one, so the same document generated differently either side of a version
     * bump; and within 3.0 the boolean spellings missed the
     * {@code instanceof Schema} test that the empty-schema spelling passed,
     * falling back to a {@code String} value type. {@code false} — which says
     * there are to be <em>no</em> additional properties — generated a dictionary
     * on both counts.
     *
     * <p>{@link Oas31ParityTest} pins the 3.0-versus-3.1 half of this; the value
     * types themselves are asserted here.
     */
    @Test
    void everyAdditionalPropertiesSpellingAgrees() throws IOException {
        Path spec = Path.of("src/test/resources/additionalproperties.yaml");
        new JavaCodegen(params()).generate(spec, result);

        // "any value is allowed": both spellings, one type, and one that can
        // actually hold a nested object.
        assertThat(Files.readString(result.resolve("com/example/dto/DictTrue.java")))
                .contains("private Map<String, Object> additionalProperties");
        assertThat(Files.readString(result.resolve("com/example/dto/DictEmpty.java")))
                .contains("private Map<String, Object> additionalProperties");

        // "no additional properties": no dictionary at all.
        assertThat(Files.readString(result.resolve("com/example/dto/DictFalse.java")))
                .doesNotContain("additionalProperties");

        // A typed dictionary is untouched by any of this.
        assertThat(Files.readString(result.resolve("com/example/dto/DictTyped.java")))
                .contains("private Map<String, Boolean> additionalProperties");

        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    /**
     * A {@code $ref} into a schema's {@code $defs} has no generated class to name.
     * It used to resolve to the pointer's last segment, so the output referred to
     * a class that was never generated and did not compile — a failure the user
     * met as a compiler error in their own build, about a name they never wrote.
     */
    @Test
    void refIntoDefsIsReported() {
        Path spec = Path.of("src/test/resources/oas31defs.yaml");
        for (Codegen<?> codegen : List.of(new JavaCodegen(params()), new KotlinCodegen(params()))) {
            assertThatThrownBy(() -> codegen.generate(spec, result))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("$defs/Local")
                    .hasMessageContaining("#/components/schemas/");
        }
    }

    @Test
    void kotlinMapsEvery31Keyword() throws IOException {
        new KotlinCodegen(params()).generate(SPEC, result);
        String keywords = Files.readString(result.resolve("com/example/dto/Keywords.kt"));

        assertThat(keywords).contains("looseArray: List<Any>");
        assertThat(keywords).contains("tuple: List<Any>");
        assertThat(keywords).contains("tupleWithItems: List<Any>");

        assertThat(keywords).contains("constString: String");
        assertThat(keywords).contains("constInt: Int");
        assertThat(keywords).contains("constBool: Boolean");

        assertThat(keywords).contains("multiType: Any");
        assertThat(keywords).contains("boolSchema: Any");

        assertThat(keywords).contains("octetsEncoded: ByteArray");
        assertThat(keywords).contains("octetsEncodingOnly: ByteArray");
        assertThat(keywords).contains("octetsLegacy: ByteArray");
        assertThat(keywords).contains("octetsMediaTypeOnly: String");

        assertThat(keywords).contains("public enum class Colour");
        assertThat(keywords).contains("RED");
        assertThat(keywords).contains("GREEN");
        assertThat(keywords).doesNotContain("NULL");

        GeneratedCodeCompiler.assertKotlinCompiles(result);
    }

    @Test
    void kotlinMapsFreeFormDictionariesToAny() throws IOException {
        new KotlinCodegen(params()).generate(SPEC, result);

        // Any?, not Any: a dictionary value is a JSON value like any other and
        // takes the generator's usual "nullable unless the schema says otherwise"
        // default. What matters here is that the two spellings agree and that
        // neither lands on String.
        assertThat(Files.readString(result.resolve("com/example/dto/FreeDictTrue.kt")))
                .contains("Map<String, Any?>");
        assertThat(Files.readString(result.resolve("com/example/dto/FreeDictEmpty.kt")))
                .contains("Map<String, Any?>");
    }
}
