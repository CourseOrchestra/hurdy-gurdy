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
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


public abstract class Codegen<T> {

    /**
     * Stray {@code nullable}s beyond this many are summarised in a single final
     * line: a 3.0 document bumped to 3.1 wholesale can carry hundreds, and a
     * warning per occurrence would then bury the rest of the build log.
     */
    private static final int MAX_REPORTED_WARNINGS = 10;

    private final GeneratorParams params;
    private OpenAPI openAPI;
    private final Map<ClassCategory, List<T>> typeSpecs = new EnumMap<>(ClassCategory.class);
    private final List<TypeSpecExtractor<T>> typeSpecExtractors;
    private final TypeDefiner<T> typeDefiner;
    private Consumer<String> warningListener = System.err::println;


    public Codegen(GeneratorParams params, TypeProducersFactory<T> typeProducersFactory) {
        this.params = params;
        typeDefiner = typeProducersFactory.createTypeDefiner(this::addTypeSpec);
        typeSpecExtractors = typeProducersFactory.typeSpecExtractors(typeDefiner);
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
        warnStrayNullable(openAPI);
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

    public void generate(Path sourceFile, Path resultDirectory) throws IOException {
        parse(sourceFile);

        if (!Files.isDirectory(resultDirectory)) throw new IllegalArgumentException(
                String.format("File %s is not a directory", resultDirectory));

        typeDefiner.init(sourceFile);
        typeSpecExtractors.forEach(e -> e.extractTypeSpecs(openAPI, this::addTypeSpec));
        generate(resultDirectory);
    }


    void generate(Path resultDirectory) throws IOException {
        for (Map.Entry<ClassCategory, List<T>> typeSpecsEntry : typeSpecs.entrySet()) {
            for (T typeSpec : typeSpecsEntry.getValue()) {
                final String packageName = String.join(".", params.getRootPackage(),
                        typeSpecsEntry.getKey().getPackageName());
                writeFile(resultDirectory, packageName, typeSpec);
            }
        }
    }

    public void addTypeSpec(ClassCategory classCategory, T typeSpec) {
        List<T> specList = this.typeSpecs.computeIfAbsent(classCategory, n -> new ArrayList<>());
        specList.add(typeSpec);
    }

    abstract void writeFile(Path resultDirectory, String packageName, T typeSpec) throws IOException;
}
