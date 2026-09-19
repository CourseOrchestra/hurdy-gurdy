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

package ru.curs.hurdygurdy.emit;

import ru.curs.hurdygurdy.ClassCategory;
import ru.curs.hurdygurdy.GeneratorParams;
import ru.curs.hurdygurdy.spec.LinkedDocuments;
import ru.curs.hurdygurdy.spec.SchemaSemantics;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static ru.curs.hurdygurdy.spec.SchemaSemantics.CLASS_NAME_PATTERN;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.FILE_NAME_PATTERN;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.checkReferenceIsGeneratable;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.extractGroup;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isArraySchema;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isNullableSchema;

/**
 * Turns a schema into the generated type for one target language.
 *
 * <p>What is left here is everything that needs the generator's
 * <em>configuration</em> to answer — the root package a {@code $ref} resolves
 * into, whether an array alias is inlined, whether a property name must be
 * pinned with {@code @JsonProperty}. Questions answerable from the
 * specification alone live in {@link SchemaSemantics}, and the parsing and
 * caching of linked documents in {@link LinkedDocuments}.
 *
 * <p>The class is deliberately free of any code-generation library: a subclass
 * produces {@code T} and nothing here knows what {@code T} is. It used to
 * declare both {@code defineJavaType} and {@code defineKotlinType}, whose base
 * implementations threw — so this class imported JavaPoet <em>and</em>
 * KotlinPoet, and {@code <T>} constrained nothing. Each definer now declares its
 * own, and the extractors hold the definer they actually need.
 *
 * @param <T> the generated type: a JavaPoet or KotlinPoet {@code TypeSpec}
 */
public abstract class TypeDefiner<T> {

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
    private final LinkedDocuments linkedDocuments = new LinkedDocuments();
    private final Set<String> aliasesBeingInlined = new HashSet<>();

    /**
     * Creates a type definer.
     *
     * @param params             what to generate and how
     * @param typeSpecBiConsumer receives types generated as a side effect of
     *                           resolving another, such as an inline object
     */
    public TypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.params = params;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    /**
     * The type generated for a component schema: an enum, an array alias or a
     * class, whichever the schema describes.
     *
     * @param name    the component's name
     * @param schema  the component's schema
     * @param openAPI the document it was declared in
     * @return the generated type
     */
    public final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return getEnum(name, schema);
        } else if (isArraySchema(schema)) {
            return getArrayAlias(name, schema, openAPI);
        } else {
            return getDTOClass(name, schema, openAPI);
        }
    }

    /**
     * The schema behind a same-file reference to an array alias (a named
     * component schema that is a plain {@code type: array}), or null when the
     * reference must stay a class reference: {@code generateAliasAsModel} is
     * set, the reference points into another file (whose schemas are not
     * visible here), or the referenced schema is not an array.
     *
     * @param ref     the reference to inspect
     * @param openAPI the document the reference was written in
     * @return the aliased array schema, or null
     */
    public final Schema<?> inlinableArrayAlias(String ref, OpenAPI openAPI) {
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
     *
     * @param ref    the alias being inlined
     * @param action the recursive type definition to guard
     * @param <R>    whatever {@code action} produces
     * @return the result of {@code action}
     */
    public final <R> R inliningAlias(String ref, Supplier<R> action) {
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
     * @return the name to pin, or null when none is needed
     */
    final String jsonNameOverride(String key, String propertyName) {
        if (!params.isForceSnakeCaseForProperties()) {
            // No @JsonNaming is emitted, and the identifier IS the spec key.
            return null;
        }
        return SNAKE_CASE_STRATEGY.translate(propertyName).equals(key) ? null : key;
    }

    abstract T getEnum(String name, Schema<?> schema);

    abstract T getDTOClass(String name, Schema<?> schema, OpenAPI openAPI);

    /**
     * The model generated for an array alias when
     * {@link GeneratorParams#isGenerateAliasAsModel()} is set: a class extending
     * {@code ArrayList<Item>} (mirroring openapi-generator's
     * {@code generateAliasAsModel} output), so it serializes as a plain JSON
     * array.
     *
     * @param name    the component name the alias was declared under
     * @param schema  the array schema
     * @param openAPI the document it was declared in
     * @return the generated model
     */
    abstract T getArrayAlias(String name, Schema<?> schema, OpenAPI openAPI);

    /**
     * Prepares the definer for one generation run, discarding what the previous
     * one cached.
     *
     * @param currentSourceFile the specification being generated
     * @param listener          receives warnings raised while reading linked
     *                          documents
     */
    public void init(Path currentSourceFile, Consumer<String> listener) {
        linkedDocuments.reset(currentSourceFile, listener);
        externalClasses.clear();
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
                    SchemaSemantics.nullableOf(currentOpenAPI, className, true));
        } else {
            return externalClasses.computeIfAbsent(ref, f -> {
                OpenAPI openAPI = definingDocument(currentOpenAPI, ref);
                String packageName = Optional.ofNullable(openAPI.getExtensions())
                        .map(e -> e.get("x-package"))
                        .map(String.class::cast)
                        .orElseThrow(() -> new IllegalStateException(
                                String.format("x-package not defined for externally linked file %s ",
                                        linkedDocuments.sourceFile().resolveSibling(fileName))));
                return new DTOMeta(className, packageName, fileName,
                        SchemaSemantics.nullableOf(openAPI, className, true));
            });
        }
    }

    /**
     * The document that defines what {@code ref} points at.
     *
     * @param currentOpenAPI the document the reference was written in
     * @param ref            the reference to follow
     * @return the declaring document
     * @see LinkedDocuments
     */
    public final OpenAPI definingDocument(OpenAPI currentOpenAPI, String ref) {
        return linkedDocuments.documentOf(currentOpenAPI, ref);
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
     * @param schema  the schema at the point of use
     * @param openAPI the document it was written in
     * @return whether a null value is permitted there
     * @see ru.curs.hurdygurdy.spec.StrayNullableCheck
     */
    public final boolean isNullableType(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) {
            return false;
        }
        String ref = schema.get$ref();
        if (ref == null) {
            return isNullableSchema(schema);
        }
        return SchemaSemantics.nullableOf(
                definingDocument(openAPI, ref), extractGroup(ref, CLASS_NAME_PATTERN), false);
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
     *
     * @param schema  the schema at the point of use
     * @param openAPI the document it was written in
     * @return the default, rendered as a string, or null
     */
    public final String effectiveDefault(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) {
            return null;
        }
        if (schema.getDefault() != null) {
            return schema.getDefault().toString();
        }
        String ref = schema.get$ref();
        return ref == null
                ? null
                : SchemaSemantics.defaultOf(
                        definingDocument(openAPI, ref), extractGroup(ref, CLASS_NAME_PATTERN));
    }
}
