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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the two back ends give one schema one type.
 *
 * <p>"What does this schema mean as a type?" is a question about the
 * specification, and it used to be answered twice — once in
 * {@code defineJavaType}, once in {@code defineKotlinType} — from two copies of
 * the same forty-line decision tree. The copies had drifted: for a schema that
 * describes an object without declaring {@code type: object} and without a
 * {@code title}, Java named a class after the position it was found in and
 * Kotlin gave up and said {@code Any}. The {@code browseruse} fixture contains
 * exactly that shape, and the two approval suites had each pinned their own
 * answer to it without anything noticing they disagreed.
 *
 * <p>The snapshots pin what each language emits; this pins that the set of types
 * is the same one on both sides, which is the fact no single-language snapshot
 * can state.
 */
class TypeParityTest {

    @TempDir
    private Path temp;

    /**
     * The types {@code inlinetypeparity.yaml} describes. Every one of the three
     * inline ones is untitled, so each is named after the position it was found
     * in — which is the naming the two back ends used to disagree about.
     */
    private static final List<String> EXPECTED = List.of(
            "AnonymousObject", "NamedInline", "ScalarUnionItem", "Thing", "UntypedObject");

    @Test
    void bothBackEndsGenerateTheSameTypes() throws IOException {
        assertThat(dtoNames(java())).containsExactlyElementsOf(EXPECTED);
        assertThat(dtoNames(kotlin())).containsExactlyElementsOf(EXPECTED);
    }

    @Test
    void anUntitledInlineTypeIsNamedAfterItsPositionInBothLanguages() throws IOException {
        assertThat(source(java(), "Thing.java"))
                .contains("private List<ScalarUnionItem> scalarUnion;")
                .contains("private UntypedObject untypedObject;");
        assertThat(source(kotlin(), "Thing.kt"))
                .contains("List<ScalarUnionItem>")
                .contains("UntypedObject");
    }

    private Path java() throws IOException {
        Path output = Files.createDirectories(temp.resolve("java"));
        new JavaCodegen(GeneratorParams.rootPackage("com.example"))
                .generate(Path.of("src/test/resources/inlinetypeparity.yaml"), output);
        return output;
    }

    private Path kotlin() throws IOException {
        Path output = Files.createDirectories(temp.resolve("kotlin"));
        new KotlinCodegen(GeneratorParams.rootPackage("com.example"))
                .generate(Path.of("src/test/resources/inlinetypeparity.yaml"), output);
        return output;
    }

    /** The simple names of the generated DTOs, sorted, whatever the file extension. */
    private static List<String> dtoNames(Path output) throws IOException {
        try (Stream<Path> walk = Files.walk(output.resolve("com/example/dto"))) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().replaceAll("\\.(java|kt)$", ""))
                    .sorted()
                    .toList();
        }
    }

    private static String source(Path output, String fileName) throws IOException {
        return Files.readString(output.resolve("com/example/dto").resolve(fileName));
    }
}
