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

import ru.curs.hurdygurdy.emit.TypeDefiner;
import ru.curs.hurdygurdy.spec.SchemaNormalizer;
import ru.curs.hurdygurdy.spec.StrayNullableCheck;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


/**
 * Runs a generation: parses and canonicalises a specification, drives the
 * extractors over it, and writes the types they produce.
 *
 * @param <T> the generated type: a JavaPoet or KotlinPoet {@code TypeSpec}
 */
public abstract class Codegen<T> {

    /**
     * Stray {@code nullable}s beyond this many are summarised in a single final
     * line: a 3.0 document bumped to 3.1 wholesale can carry hundreds, and a
     * warning per occurrence would then bury the rest of the build log.
     */
    private static final int MAX_REPORTED_WARNINGS = 10;

    private final GeneratorParams params;
    private OpenAPI openAPI;
    private final Map<ClassCategory, Map<String, T>> typeSpecs = new EnumMap<>(ClassCategory.class);
    private final List<TypeSpecExtractor<T>> typeSpecExtractors;
    private final TypeDefiner<T> typeDefiner;
    private Consumer<String> warningListener = System.err::println;


    /**
     * Creates a generator for one target language.
     *
     * @param params               what to generate and how
     * @param typeProducersFactory supplies the language's type definer and the
     *                             extractors that drive it
     * @param <D>                  the concrete type definer
     */
    public <D extends TypeDefiner<T>> Codegen(GeneratorParams params,
                                              TypeProducersFactory<T, D> typeProducersFactory) {
        this.params = params;
        D definer = typeProducersFactory.createTypeDefiner(this::addTypeSpec);
        typeDefiner = definer;
        typeSpecExtractors = typeProducersFactory.typeSpecExtractors(definer);
    }

    private void parse(Path sourceFile) throws IOException {
        if (!Files.isReadable(sourceFile)) throw new IllegalArgumentException(
                String.format("File %s is not readable", sourceFile));
        ParseOptions parseOptions = new ParseOptions();
        SwaggerParseResult result = new OpenAPIParser()
                .readContents(Files.readString(sourceFile), null, parseOptions);
        openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalArgumentException(String.join(String.format("%n"), result.getMessages()));
        }
        // Order matters: the stray-nullable check reads the document exactly as
        // written, so it has to run before anything rewrites it.
        warnStrayNullable(openAPI);
        SchemaNormalizer.normalize(openAPI, warningListener);
    }

    /**
     * Sets where generator warnings go; by default they are printed to
     * {@code System.err}. The Maven plugin points this at the build log.
     *
     * @param listener receives one message per warning
     */
    public void setWarningListener(Consumer<String> listener) {
        this.warningListener = listener;
    }

    /**
     * Reports every {@code nullable} keyword left over in a 3.1 document. The
     * keyword was removed in OpenAPI 3.1 and is therefore ignored (see
     * {@link StrayNullableCheck}); saying so out loud is what keeps a
     * half-finished 3.0 &rarr; 3.1 migration from silently turning nullable
     * fields into non-null ones.
     */
    private void warnStrayNullable(OpenAPI api) {
        List<String> locations = StrayNullableCheck.locations(api);
        for (String location : locations.subList(0, Math.min(locations.size(), MAX_REPORTED_WARNINGS))) {
            // Deliberately not "use type: [X, \"null\"] instead": that is the right
            // advice only for `nullable: true`. For `nullable: false` a null union
            // would reverse the meaning, and a $ref carries no type to extend.
            warningListener.accept(String.format(
                    "hurdy-gurdy: 'nullable' is not an OpenAPI 3.1 keyword and is ignored at %s; "
                            + "remove it, or — if the value really may be null — say so with "
                            + "type: [<type>, \"null\"]", location));
        }
        if (locations.size() > MAX_REPORTED_WARNINGS) {
            warningListener.accept(String.format(
                    "hurdy-gurdy: ... and %d more ignored 'nullable' keyword(s) in this 3.1 document",
                    locations.size() - MAX_REPORTED_WARNINGS));
        }
    }

    /**
     * Generates sources for one specification.
     *
     * @param sourceFile      the specification to read
     * @param resultDirectory an existing directory to write the sources into
     * @throws IOException if the specification cannot be read or the sources
     *                     cannot be written
     */
    public void generate(Path sourceFile, Path resultDirectory) throws IOException {
        parse(sourceFile);

        if (!Files.isDirectory(resultDirectory)) throw new IllegalArgumentException(
                String.format("File %s is not a directory", resultDirectory));

        typeDefiner.init(sourceFile, warningListener);
        typeSpecExtractors.forEach(e -> e.extractTypeSpecs(openAPI, this::addTypeSpec));
        generate(resultDirectory);
    }


    void generate(Path resultDirectory) throws IOException {
        for (Map.Entry<ClassCategory, Map<String, T>> typeSpecsEntry : typeSpecs.entrySet()) {
            for (T typeSpec : typeSpecsEntry.getValue().values()) {
                final String packageName = String.join(".", params.getRootPackage(),
                        typeSpecsEntry.getKey().getPackageName());
                writeFile(resultDirectory, packageName, typeSpec);
            }
        }
    }

    /**
     * Collects a generated type, at most once per name.
     *
     * <p>The same type can legitimately be reached twice — an inline titled
     * schema used by two properties is resolved once per use — and the second
     * copy was previously appended and written over the first. Identical content
     * made that invisible; differing content made it a silent loss, because the
     * last writer won and nothing said which one that was. A name is a file, so
     * one name is one type, and a genuine clash is reported rather than resolved
     * by writing order.
     *
     * @param classCategory the subpackage the type belongs in
     * @param typeSpec      the generated type
     */
    public void addTypeSpec(ClassCategory classCategory, T typeSpec) {
        Map<String, T> byName =
                this.typeSpecs.computeIfAbsent(classCategory, n -> new LinkedHashMap<>());
        String name = typeName(typeSpec);
        T existing = byName.putIfAbsent(name, typeSpec);
        if (existing != null && !existing.toString().equals(typeSpec.toString())) {
            throw new IllegalStateException(String.format(
                    "Two different types are both generated as '%s' in the %s package. A generated "
                            + "name is a file name, so one of them would silently overwrite the "
                            + "other; give the schemas distinct titles.",
                    name, classCategory.getPackageName()));
        }
    }

    /**
     * The simple name of a generated type, which is also the name of the file it
     * is written to.
     *
     * @param typeSpec the generated type
     * @return its simple name
     */
    abstract String typeName(T typeSpec);

    abstract void writeFile(Path resultDirectory, String packageName, T typeSpec) throws IOException;
}
