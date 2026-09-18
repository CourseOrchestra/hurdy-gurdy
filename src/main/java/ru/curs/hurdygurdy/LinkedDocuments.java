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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * The documents reachable from the one being generated: the root itself, and
 * each file a {@code $ref} points into, parsed once and kept.
 *
 * <p>swagger-parser is deliberately not asked to resolve cross-file references,
 * so a {@code $ref: "other.yaml#/components/schemas/X"} survives generation as
 * that literal string. Every question about {@code X} — is it nullable, does it
 * carry a default, is it an enum — therefore has to be put to the document that
 * <em>declares</em> it. Putting it to the current document instead finds nothing
 * and quietly returns the caller's fallback, which reads as "the component says
 * nothing" when in truth it was never consulted; that is what
 * <a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/621">issue
 * 621</a> surfaced.
 *
 * <p>A linked document gets the same normalization the root gets in
 * {@link Codegen}. Without it a schema would mean different things depending on
 * which file it lives in: a 3.1 {@code enum: [RED, GREEN, null]} component is
 * nullable once normalized, and merely a two-value enum when read raw through a
 * link.
 *
 * <p>One instance serves one generation run; {@link #reset(Path, Consumer)}
 * starts the next.
 */
final class LinkedDocuments {

    private final Map<String, OpenAPI> documents = new HashMap<>();
    private Path sourceFile;
    private Consumer<String> warningListener = message -> { };

    /**
     * Points this cache at a new root document and discards what it held.
     *
     * @param currentSourceFile the root specification file, against whose
     *                          directory link names are resolved
     * @param listener          receives warnings raised while normalizing a
     *                          linked document
     */
    void reset(Path currentSourceFile, Consumer<String> listener) {
        this.sourceFile = currentSourceFile;
        this.warningListener = listener;
        documents.clear();
    }

    /**
     * The document that defines what {@code ref} points at: the current one for a
     * same-file reference, or the linked file, parsed and normalized once and
     * cached.
     *
     * @param currentOpenAPI the document the reference was written in
     * @param ref            the reference to follow
     * @return the document that declares the referenced component
     */
    OpenAPI documentOf(OpenAPI currentOpenAPI, String ref) {
        Matcher matcher = SchemaSemantics.FILE_NAME_PATTERN.matcher(ref);
        String fileName = matcher.find() ? matcher.group(1) : "";
        if (fileName.isBlank()) {
            return currentOpenAPI;
        }
        return documents.computeIfAbsent(fileName, this::parse);
    }

    private OpenAPI parse(String fileName) {
        Path externalFile = sourceFile.resolveSibling(fileName);
        try {
            OpenAPI parsed = new OpenAPIParser()
                    .readContents(Files.readString(externalFile), null, new ParseOptions())
                    .getOpenAPI();
            if (parsed == null) {
                throw new IllegalStateException(
                        String.format("Could not parse externally linked file %s", externalFile));
            }
            SchemaNormalizer.normalize(parsed, message ->
                    warningListener.accept(String.format("%s [linked file %s]", message, fileName)));
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The root specification file, used to report where a linked file was
     * expected to be found.
     *
     * @return the file generation was started from
     */
    Path sourceFile() {
        return sourceFile;
    }
}
