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

import ru.curs.hurdygurdy.spec.StrayNullableCheck;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that bumping a specification from OpenAPI 3.0 to 3.1 — and changing
 * nothing else — does not change a single byte of generated code.
 *
 * <p>This is the invariant that <a
 * href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/615">issue 615</a>
 * violated. swagger-parser models a 3.1 document with {@code JsonSchema} for
 * every schema, never the 3.0 subclasses ({@code ComposedSchema},
 * {@code ArraySchema}, …), so any generator logic keyed on the schema's Java
 * class silently stopped firing under 3.1: {@code allOf} inheritance, the
 * {@code oneOf} supertype, sealed hierarchies and discriminator wiring all
 * disappeared from the output without a warning.
 *
 * <p>Rather than snapshot the 3.1 output of every fixture — which would double
 * the approval suite and freeze whatever the generator happened to emit — each
 * fixture is generated twice, once as written and once with its version string
 * rewritten to {@code 3.1.0}, and the two trees are compared. A fixture only has
 * to be listed here to be covered; there is nothing to re-approve when the
 * generator's output legitimately changes.
 *
 * <p>Parity on its own is not correctness: two identically wrong outputs pass.
 * It is the approval snapshots that pin down what the 3.0 output should be, and
 * this test that carries their verdict over to 3.1 — so the pair is only as
 * strong as the snapshot coverage of each fixture listed below.
 *
 * <p>The fixtures below are the ones for which the two versions are genuinely
 * expected to agree: they are self-contained (no {@code $ref} into a sibling
 * file, which would not resolve from the temporary directory) and they contain
 * no {@code nullable} keyword. {@code nullable} is deliberately excluded,
 * because 3.1 removed it and hurdy-gurdy therefore ignores it on purpose — see
 * {@link StrayNullableCheck} — so those fixtures <em>should</em> differ between
 * the versions, and the difference is covered by
 * {@code CodegenTest.strayNullable31IsIgnored} instead.
 */
class Oas31ParityTest {

    /**
     * Self-contained, {@code nullable}-free fixtures. Every one of these is a
     * plain 3.0 document whose 3.1 spelling is identical apart from the version
     * string.
     */
    private static final List<String> FIXTURES = List.of(
            "additionalproperties",
            "binarydownload",
            "binaryproperty",
            "binaryrawbody",
            "childlessDiscriminator",
            "commonparam",
            "dateserde",
            "deep_inheritance",
            "flatrecord",
            "inlineenum",
            "issue23988",
            "issue566",
            "issue618",
            "nestedpolyrecord",
            "oneofdiscriminator",
            "polyrecord",
            "twoparams");

    @TempDir
    private Path temp;

    static Stream<String> fixtures() {
        return FIXTURES.stream();
    }

    static Stream<Arguments> fixturesAndStyles() {
        return FIXTURES.stream().flatMap(fixture ->
                Stream.of(JavaDtoStyle.values()).map(style -> Arguments.of(fixture, style)));
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @MethodSource("fixturesAndStyles")
    void javaOutputIsUnchangedBy31(String fixture, JavaDtoStyle style) throws IOException {
        assertParity(fixture, (spec, out) -> new JavaCodegen(params().javaDtoStyle(style))
                .generate(spec, out));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void kotlinOutputIsUnchangedBy31(String fixture) throws IOException {
        assertParity(fixture, (spec, out) -> new KotlinCodegen(params()).generate(spec, out));
    }

    /**
     * Snake-case enforcement is off so that the whole list can share one
     * configuration: a handful of the fixtures carry camelCase or
     * underscore-prefixed property names that the check rejects outright. The
     * flag has no bearing on the 3.0-versus-3.1 question either way.
     */
    private static GeneratorParams params() {
        return GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(false);
    }

    private interface Generation {
        void run(Path spec, Path outputDirectory) throws IOException;
    }

    private void assertParity(String fixture, Generation generation) throws IOException {
        Map<String, String> as30 = generate(fixture, "3.0", generation);
        Map<String, String> as31 = generate(fixture, "3.1", generation);

        // File set first: a missing or extra type is the failure mode that a
        // content-only comparison reports as a confusing null.
        assertThat(as31.keySet())
                .as("generated files for %s at 3.1", fixture)
                .containsExactlyElementsOf(as30.keySet());
        for (Map.Entry<String, String> entry : as30.entrySet()) {
            assertThat(as31.get(entry.getKey()))
                    .as("%s generated from %s", entry.getKey(), fixture)
                    .isEqualTo(entry.getValue());
        }
    }

    /**
     * Generates {@code fixture} at the given major version, returning the output
     * tree as a map of relative path to file content.
     */
    private Map<String, String> generate(String fixture, String version, Generation generation)
            throws IOException {
        Path source = Path.of("src/test/resources", fixture + ".yaml");
        Path specDirectory = Files.createDirectories(temp.resolve(version).resolve("spec"));
        Path spec = specDirectory.resolve(fixture + ".yaml");
        Files.writeString(spec, at(Files.readString(source), version));

        Path output = Files.createDirectories(temp.resolve(version).resolve("out"));
        generation.run(spec, output);
        return tree(output);
    }

    /**
     * Rewrites the document's version string, leaving everything else — including
     * any {@code 3.0}-looking text elsewhere in the file — untouched.
     */
    private static String at(String document, String version) {
        Matcher matcher = VERSION_LINE.matcher(document);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "No 'openapi:' version line found to rewrite; the parity test cannot "
                            + "tell the two versions apart");
        }
        // Not replaced.equals(document): rewriting an already-3.0.0 fixture to 3.0
        // is legitimately a no-op, and must not be mistaken for a failed match.
        return matcher.replaceFirst("openapi: " + version + ".0");
    }

    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^openapi:\\s*[\"']?3\\.[01]\\.\\d+[\"']?\\s*$");

    private static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path));
            }
        }
        return files;
    }
}
