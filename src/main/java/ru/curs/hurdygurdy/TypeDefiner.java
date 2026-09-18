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

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class TypeDefiner<T> {
    protected static final Pattern CLASS_NAME_PATTERN = Pattern.compile("/([^/$]+)$");
    protected static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([^#]*)#");
    /**
     * The only {@code $ref} shape that names a generated class:
     * {@code #/components/schemas/<Name>}, optionally prefixed by another
     * file — see {@link #checkReferenceIsGeneratable(String)}.
     */
    private static final Pattern COMPONENT_SCHEMA_REF =
            Pattern.compile("^[^#]*#/components/schemas/[^/]+$");
    /**
     * A property name accepted by {@code forceSnakeCaseForProperties}: lower-case
     * snake_case, optionally prefixed by underscores (and {@code $}, for backwards compatibility).
     * A <em>leading</em> underscore is a common snake_case convention for "meta"/"private" keys
     * (YAML-anchor metadata and the like), so it is valid — see
     * <a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/566">issue 566</a>.
     */
    private static final Pattern SNAKE_CASE_PROPERTY = Pattern.compile("[_$a-z][a-z_0-9]*");
    /**
     * The very strategy the generated DTOs are annotated with, used to tell
     * whether it can reproduce a spec property name from the camel-cased
     * identifier — see {@link #jsonNameOverride(String, String)}.
     */
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE_STRATEGY =
            new PropertyNamingStrategies.SnakeCaseStrategy();

    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final GeneratorParams params;
    final Map<String, DTOMeta> externalClasses = new HashMap<>();
    private final Map<String, OpenAPI> externalDocuments = new HashMap<>();
    private Consumer<String> warningListener = message -> { };
    private final Set<String> aliasesBeingInlined = new HashSet<>();
    private Path sourceFile;

    TypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.params = params;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return getEnum(name, schema, openAPI);
        } else if (isArraySchema(schema)) {
            return getArrayAlias(name, schema, openAPI);
        } else {
            return getDTOClass(name, schema, openAPI);
        }
    }

    /**
     * The single JSON type of a schema, whichever way the document spells it.
     *
     * <p>An OpenAPI 3.0 document fills {@code type}; a 3.1 document always leaves
     * {@code type} null and fills the {@code types} set instead (JSON Schema
     * 2020-12 allows a union). {@code "null"} is stripped from that set, because
     * a 3.1 {@code type: [string, "null"]} is simply how the spec spells
     * "nullable string": the type is {@code string} and the nullability is
     * reported separately by {@link #isNullableSchema(Schema)}.
     *
     * <p>Returns null for a typeless schema and for a genuine multi-type union
     * (say {@code [string, integer]}), neither of which has a single Java/Kotlin
     * type; a schema that is <em>only</em> {@code type: "null"} keeps
     * {@code "null"}, which is how the {@code anyOf: [X, null]} unwrap
     * recognises its null member.
     */
    static String effectiveType(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.getType() != null) {
            return schema.getType();
        }
        Set<String> types = schema.getTypes();
        if (types == null) {
            return null;
        }
        List<String> named = types.stream().filter(t -> !"null".equals(t)).toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        return named.isEmpty() && types.contains("null") ? "null" : null;
    }

    /**
     * Whether a schema admits an explicit {@code null}: an OpenAPI 3.0
     * {@code nullable: true}, a 3.1 {@code type} array containing {@code "null"},
     * or the 3.1 nullable wrapper {@code anyOf: [X, {type: "null"}]} — all three
     * spellings of the same thing, so every caller deciding nullability must
     * treat them alike.
     *
     * <p>The 3.1 {@code nullable} keyword is deliberately <em>not</em> consulted.
     * OpenAPI 3.1 removed it outright (JSON Schema 2020-12 has no such keyword),
     * so swagger-parser does not populate {@code getNullable()} for a 3.1
     * document — it drops the stray keyword into the schema's extension map
     * along with every other unrecognised keyword. Honouring it there would
     * revive a keyword the spec deleted and make hurdy-gurdy disagree with every
     * other 3.1 tool; {@link StrayNullableCheck} warns about it instead.
     */
    static boolean isNullableSchema(Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            return true;
        }
        Set<String> types = schema.getTypes();
        return types != null && types.contains("null") || isNullableAnyOf(schema);
    }

    /**
     * Whether a schema is the two-member nullable wrapper
     * {@code anyOf: [X, {type: "null"}]} — the form the type definers unwrap to
     * the type of {@code X}. A {@code oneOf} is not treated this way: the
     * generator reads {@code oneOf} as a polymorphic base, not as a wrapper.
     */
    @SuppressWarnings("rawtypes")
    private static boolean isNullableAnyOf(Schema<?> schema) {
        List<Schema> anyOf = schema.getAnyOf();
        return anyOf != null && anyOf.size() == 2
                && anyOf.stream().anyMatch(member -> "null".equals(effectiveType(member)));
    }

    /**
     * Whether a schema that declares no single JSON type nevertheless describes
     * an object — it has properties, a dictionary, composition or a
     * discriminator. A schema with none of those admits any value, and must map
     * to {@code Object}/{@code Any} rather than to a class invented from
     * whatever name happened to be in scope.
     *
     * <p>Only consulted for a schema with no effective type; an explicit
     * {@code type: object} is a class whether or not it declares anything.
     */
    static boolean describesObject(Schema<?> schema) {
        return schema.getProperties() != null && !schema.getProperties().isEmpty()
                || schema.getAdditionalProperties() != null
                || schema.getAllOf() != null
                || schema.getOneOf() != null
                || schema.getAnyOf() != null
                || schema.getDiscriminator() != null;
    }

    static boolean isArraySchema(Schema<?> schema) {
        return "array".equals(effectiveType(schema));
    }

    /**
     * The schema behind a same-file reference to an array alias (a named
     * component schema that is a plain {@code type: array}), or null when the
     * reference must stay a class reference: {@code generateAliasAsModel} is
     * set, the reference points into another file (whose schemas are not
     * visible here), or the referenced schema is not an array.
     */
    final Schema<?> inlinableArrayAlias(String ref, OpenAPI openAPI) {
        if (params.isGenerateAliasAsModel() || !extractGroup(ref, FILE_NAME_PATTERN).isBlank()) {
            return null;
        }
        Schema<?> schema = Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(extractGroup(ref, CLASS_NAME_PATTERN)))
                .orElse(null);
        return schema != null && isArraySchema(schema) ? schema : null;
    }

    /**
     * Runs {@code action} (the recursive type definition that inlines the alias
     * {@code ref} points to) guarding against alias cycles, which cannot be
     * inlined and would otherwise recurse forever.
     */
    final <R> R inliningAlias(String ref, Supplier<R> action) {
        String name = extractGroup(ref, CLASS_NAME_PATTERN);
        if (!aliasesBeingInlined.add(name)) {
            throw new IllegalStateException(String.format(
                    "Array alias '%s' references itself and cannot be inlined; "
                            + "set generateAliasAsModel to generate a class for it", name));
        }
        try {
            return action.get();
        } finally {
            aliasesBeingInlined.remove(name);
        }
    }

    @SuppressWarnings("unchecked")
    final List<String> getExtendsList(Schema<?> schema) {
        List<String> extendsList = new ArrayList<>();
        Optional.ofNullable(schema.getExtensions()).map(e -> e.get("x-extends"))
                .ifPresent(e -> {
                            if (e instanceof String s) {
                                extendsList.add(s);
                            } else if (e instanceof List) {
                                extendsList.addAll((List<String>) e);
                            }
                        }
                );
        return extendsList;
    }

    final String getEnumName(Schema<?> schema, String typeNameFallback) {
        String simpleName = schema.getTitle();
        if (simpleName == null) {
            simpleName = typeNameFallback;
        }
        if (simpleName == null) {
            throw new IllegalStateException("Inline enum schema must have a title");
        }
        return simpleName;
    }

    final void checkPropertyName(String name, String propertyName) {
        if (params.isForceSnakeCaseForProperties()
                && !SNAKE_CASE_PROPERTY.matcher(propertyName).matches()) throw new IllegalStateException(
                String.format("Property '%s' of schema '%s' is not in snake case",
                        propertyName, name)
        );
    }

    /**
     * The JSON name that must be pinned with an explicit {@code @JsonProperty} on
     * the generated property, or {@code null} when the configured Jackson naming
     * already reproduces the spec key on its own (the overwhelmingly common case,
     * which stays annotation-free).
     *
     * <p>In snake-case mode the generator camel-cases the spec key and leans on
     * {@code @JsonNaming(SnakeCaseStrategy)} to turn it back. That round trip is
     * <em>lossy</em> for underscores at the edges of a name: {@code SnakeCaseStrategy}
     * silently drops the first leading underscore, so {@code _anchors} would go on
     * the wire as {@code anchors} and {@code __meta} as {@code _meta}
     * (<a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/566">issue 566</a>);
     * a trailing underscore is lost in the camel-casing itself. The check is the
     * round trip itself rather than an underscore test, so any key the strategy
     * cannot reproduce is pinned.
     *
     * @param key          the property name as written in the specification
     * @param propertyName the generated Java/Kotlin property identifier
     */
    final String jsonNameOverride(String key, String propertyName) {
        if (!params.isForceSnakeCaseForProperties()) {
            // No @JsonNaming is emitted, and the identifier IS the spec key.
            return null;
        }
        return SNAKE_CASE_STRATEGY.translate(propertyName).equals(key) ? null : key;
    }

    final Map<String, String> getSubclassMapping(Schema<?> schema) {
        return Optional.ofNullable(schema.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
    }

    abstract T getEnum(String name, Schema<?> schema, OpenAPI openAPI);

    abstract T getDTOClass(String name, Schema<?> schema, OpenAPI openAPI);

    /**
     * The model generated for an array alias when
     * {@link GeneratorParams#isGenerateAliasAsModel()} is set: a class extending
     * {@code ArrayList<Item>} (mirroring openapi-generator's
     * {@code generateAliasAsModel} output), so it serializes as a plain JSON
     * array.
     */
    abstract T getArrayAlias(String name, Schema<?> schema, OpenAPI openAPI);

    com.palantir.javapoet.TypeName defineJavaType(Schema<?> schema,
                                                  OpenAPI openAPI,
                                                  com.palantir.javapoet.TypeSpec.Builder parent,
                                                  String typeNameFallback) {
        throw new IllegalStateException();
    }

    com.squareup.kotlinpoet.TypeName defineKotlinType(Schema<?> schema,
                                                      OpenAPI openAPI,
                                                      com.squareup.kotlinpoet.TypeSpec.Builder parent,
                                                      String typeNameFallback,
                                                      Boolean nullableOverride) {
        throw new IllegalStateException();
    }

    void init(Path currentSourceFile, Consumer<String> listener) {
        this.sourceFile = currentSourceFile;
        this.warningListener = listener;
        externalClasses.clear();
        externalDocuments.clear();
        aliasesBeingInlined.clear();
    }


    DTOMeta getReferencedTypeInfo(OpenAPI currentOpenAPI, String ref) {
        checkReferenceIsGeneratable(ref);
        String fileName = extractGroup(ref, FILE_NAME_PATTERN);
        String className = extractGroup(ref, CLASS_NAME_PATTERN);
        if (fileName.isBlank()) {
            return new DTOMeta(className,
                    params.getRootPackage(),
                    fileName,
                    getNullable(currentOpenAPI, className, true));
        } else {
            return externalClasses.computeIfAbsent(ref, f -> {
                OpenAPI openAPI = definingDocument(currentOpenAPI, ref);
                String packageName = Optional.ofNullable(openAPI.getExtensions())
                        .map(e -> e.get("x-package"))
                        .map(String.class::cast)
                        .orElseThrow(() -> new IllegalStateException(
                                String.format("x-package not defined for externally linked file %s ",
                                        sourceFile.resolveSibling(fileName))));
                return new DTOMeta(className, packageName, fileName, getNullable(openAPI, className, true));
            });
        }
    }

    /**
     * The document that defines what {@code ref} points at: the current one for
     * a same-file reference, or the linked file, parsed once and cached.
     *
     * <p>Every question about a referenced component — is it nullable, does it
     * carry a default, is it an enum — has to be asked of the document that
     * declares it. Asking the current document about {@code other.yaml#/...}
     * finds nothing and quietly returns the caller's default, which reads as
     * "the component says nothing" when in truth it was never consulted.
     */
    private OpenAPI definingDocument(OpenAPI currentOpenAPI, String ref) {
        Matcher matcher = FILE_NAME_PATTERN.matcher(ref);
        String fileName = matcher.find() ? matcher.group(1) : "";
        if (fileName.isBlank()) {
            return currentOpenAPI;
        }
        return externalDocuments.computeIfAbsent(fileName, name -> {
            Path externalFile = sourceFile.resolveSibling(name);
            try {
                OpenAPI parsed = new OpenAPIParser()
                        .readContents(Files.readString(externalFile), null, new ParseOptions())
                        .getOpenAPI();
                if (parsed == null) {
                    throw new IllegalStateException(
                            String.format("Could not parse externally linked file %s", externalFile));
                }
                // The same normalization the root document gets in Codegen.parse.
                // Without it a schema would mean different things depending on
                // which file it lives in: a 3.1 `enum: [RED, GREEN, null]`
                // component is nullable once normalized, and merely a
                // two-value enum when read raw through a link.
                SchemaNormalizer.normalize(parsed, message ->
                        warningListener.accept(String.format("%s [linked file %s]", message, name)));
                return parsed;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    protected boolean getNullable(OpenAPI currentOpenAPI, String className, Boolean defaultValue) {
        Schema<?> schema = Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .orElse(null);
        if (isNullableSchema(schema)) {
            // 3.0 `nullable: true`, or a 3.1 `type: [..., "null"]` component.
            return true;
        }
        return schema == null || schema.getNullable() == null ? defaultValue : schema.getNullable();
    }

    /**
     * Whether a schema used as a parameter, request-body or response type says
     * of itself that it admits {@code null} — {@code nullable: true}, a 3.1
     * {@code "null"} among its types, or the {@code anyOf: [X, null]} wrapper;
     * for a {@code $ref} the question is asked of the referenced component
     * schema (which, declaring nothing, is NOT nullable).
     *
     * <p>Deliberately separate from whether the value may be <em>absent</em> —
     * an optional parameter, a request body that is not {@code required}. A
     * Kotlin type is nullable when either holds, and the two are decided by
     * different parts of the document, so they are asked separately. Absence is
     * why a {@code $ref} <em>property</em> defaults to nullable while a list
     * <em>element</em> does not: the property may be left out, the element
     * cannot. Both ask this method the null question and answer the absence
     * question themselves.
     *
     * <p>This is the single answer to that question, for every position —
     * property, array element, parameter, request body, response. It used to be
     * asked in three slightly different ways, and that disagreement is what
     * <a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/620">issue 620</a>
     * surfaced; keep it here rather than growing a fourth copy.
     *
     * <p>In OpenAPI 3.0 a {@code $ref} can only ever be answered for by the
     * component it names, never at the point of use: 3.0 ignores keywords
     * written beside a {@code $ref}, so {@code {$ref: X, nullable: false}} says
     * nothing at all. A 3.1 document states it at the site instead, with
     * {@code anyOf: [{$ref: X}, {type: "null"}]}.
     *
     * @see StrayNullableCheck
     */
    final boolean isNullableType(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) {
            return false;
        }
        String ref = schema.get$ref();
        if (ref == null) {
            return isNullableSchema(schema);
        }
        return getNullable(definingDocument(openAPI, ref), extractGroup(ref, CLASS_NAME_PATTERN), false);
    }

    /**
     * The default value that applies to {@code schema}: its own, or — for a
     * {@code $ref} — the one the referenced component declares. Null when
     * neither does.
     *
     * <p>A parameter with a default is never absent from the handler's point of
     * view, because the generator emits that default into the annotation
     * ({@code @RequestParam(defaultValue = ...)}, {@code @DefaultValue}) and the
     * framework substitutes it. The two must be decided from the same answer, so
     * both the annotation and the nullability read this method.
     */
    final String effectiveDefault(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) {
            return null;
        }
        if (schema.getDefault() != null) {
            return schema.getDefault().toString();
        }
        String ref = schema.get$ref();
        return ref == null
                ? null
                : getDefault(definingDocument(openAPI, ref), extractGroup(ref, CLASS_NAME_PATTERN));
    }

    protected String getDefault(OpenAPI currentOpenAPI, String className) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getDefault)
                .map(Object::toString)
                .orElse(null);
    }

    protected boolean isEnum(OpenAPI currentOpenAPI, String className) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getEnum)
                .map(l -> !l.isEmpty())
                .orElse(false);

    }

    /**
     * Rejects a {@code $ref} that does not name a component schema.
     *
     * <p>hurdy-gurdy generates one class per entry in
     * {@code components/schemas}, so that is the only pointer it can turn into a
     * type name. JSON Schema 2020-12 — and therefore OpenAPI 3.1 — allows a
     * pointer to walk further in, most usefully into a schema's private
     * {@code $defs}. Such a reference has no generated class to name, and
     * {@link #CLASS_NAME_PATTERN} would quietly reduce it to the pointer's last
     * segment: the output then refers to a class nobody generated and does not
     * compile, which the user meets as a compiler error in their own build about
     * a name they never wrote. Saying so here, naming the offending pointer, is
     * the whole improvement.
     */
    private static void checkReferenceIsGeneratable(String ref) {
        if (!COMPONENT_SCHEMA_REF.matcher(ref).matches()) {
            throw new IllegalStateException(String.format(
                    "Unsupported $ref '%s': hurdy-gurdy generates a class per component schema, so a "
                            + "reference must point at '#/components/schemas/<Name>' (optionally "
                            + "prefixed by another file). Move the schema into components/schemas "
                            + "and reference it from there.", ref));
        }
    }

    protected String extractGroup(String ref, Pattern pattern) {
        Matcher matcher = pattern.matcher(ref);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("Illegal ref:" + ref);
        }
    }
}
