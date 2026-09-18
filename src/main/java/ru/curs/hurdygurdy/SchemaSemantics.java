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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a schema <em>says</em>, as opposed to how it is spelled in any target
 * language.
 *
 * <p>Every question here is answered from the specification alone: does this
 * schema admit null, is it an array, is it a polymorphic base, which class does
 * this {@code $ref} name. None of it depends on Java or Kotlin, and none of it
 * depends on the generator's configuration — a question that needs
 * {@link GeneratorParams} to answer is generator <em>policy</em> and stays on
 * {@link TypeDefiner}.
 *
 * <p>Gathering these here is what stops them being answered twice. They used to
 * live partly on {@code TypeDefiner} and partly, in a second copy, in each of
 * the two type definers — {@link #polymorphicMembers(Schema)} was written out
 * once per language — and a pair of copies that drift is how the generator comes
 * to mean different things in Java and in Kotlin. There is one answer to each
 * question and it is here; see {@code ApiParityTest} for what the alternative
 * costs.
 */
final class SchemaSemantics {

    /** The class name a {@code $ref} names: the last segment of the pointer. */
    static final Pattern CLASS_NAME_PATTERN = Pattern.compile("/([^/$]+)$");
    /** The file a {@code $ref} points into, empty for a same-file reference. */
    static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([^#]*)#");
    /**
     * The only {@code $ref} shape that names a generated class:
     * {@code #/components/schemas/<Name>}, optionally prefixed by another
     * file — see {@link #checkReferenceIsGeneratable(String)}.
     */
    private static final Pattern COMPONENT_SCHEMA_REF =
            Pattern.compile("^[^#]*#/components/schemas/[^/]+$");

    private SchemaSemantics() {
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
     *
     * @param schema the schema to read, may be null
     * @return the single JSON type, or null when there is not exactly one
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
     *
     * @param schema the schema to read, may be null
     * @return whether the schema permits a null value
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
     *
     * @param schema the schema to read
     * @return whether the schema describes an object
     */
    static boolean describesObject(Schema<?> schema) {
        return schema.getProperties() != null && !schema.getProperties().isEmpty()
                || schema.getAdditionalProperties() != null
                || schema.getAllOf() != null
                || schema.getOneOf() != null
                || schema.getAnyOf() != null
                || schema.getDiscriminator() != null;
    }

    /**
     * Whether the schema is an array.
     *
     * @param schema the schema to read, may be null
     * @return whether its single JSON type is {@code array}
     */
    static boolean isArraySchema(Schema<?> schema) {
        return "array".equals(effectiveType(schema));
    }

    /**
     * The member subschemas of a polymorphic container — a {@code oneOf}, or a
     * top-level {@code anyOf} of two-or-more non-null {@code $ref}s (treated the
     * same way). Returns an empty list for a plain schema, a nullable
     * {@code anyOf:[X,null]}, or a single-ref anyOf. This is the single predicate
     * for "is this schema a DEDUCTION-based polymorphic interface".
     *
     * @param schema the schema to read
     * @return the polymorphic members, empty when the schema is not one
     */
    @SuppressWarnings("rawtypes")
    static List<Schema> polymorphicMembers(Schema<?> schema) {
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return schema.getOneOf();
        }
        List<Schema> anyOf = schema.getAnyOf();
        if (anyOf != null) {
            List<Schema> refs = anyOf.stream().filter(s -> s.get$ref() != null).toList();
            if (refs.size() >= 2) {
                return refs;
            }
        }
        return List.of();
    }

    /**
     * Whether the schema is a polymorphic base, and so becomes an interface
     * rather than a class.
     *
     * @param schema the schema to read
     * @return whether it has polymorphic members
     */
    static boolean isPolymorphicInterface(Schema<?> schema) {
        return !polymorphicMembers(schema).isEmpty();
    }

    /**
     * The types named by the {@code x-extends} extension, which the generated
     * class implements in addition to anything the schema itself implies.
     *
     * @param schema the schema to read
     * @return the fully qualified type names, possibly empty
     */
    @SuppressWarnings("unchecked")
    static List<String> getExtendsList(Schema<?> schema) {
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

    /**
     * The name of the enum generated for a schema: its {@code title}, or the name
     * of the position it was found in.
     *
     * @param schema           the enum schema
     * @param typeNameFallback the name to use when the schema has no title
     * @return the simple name of the generated enum
     */
    static String getEnumName(Schema<?> schema, String typeNameFallback) {
        String simpleName = schema.getTitle();
        if (simpleName == null) {
            simpleName = typeNameFallback;
        }
        if (simpleName == null) {
            throw new IllegalStateException("Inline enum schema must have a title");
        }
        return simpleName;
    }

    /**
     * The discriminator's explicit {@code mapping}, empty when the schema has no
     * discriminator or the discriminator declares no mapping.
     *
     * @param schema the schema to read
     * @return discriminator value to {@code $ref}
     */
    static Map<String, String> getSubclassMapping(Schema<?> schema) {
        return Optional.ofNullable(schema.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
    }

    /**
     * The component schema a name refers to in a document, or null when the
     * document declares no such component.
     *
     * @param openAPI   the document that declares the component
     * @param className the component's name under {@code components/schemas}
     * @return the schema, or null
     */
    static Schema<?> componentSchema(OpenAPI openAPI, String className) {
        return Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .orElse(null);
    }

    /**
     * Whether the named component admits null, falling back to
     * {@code defaultValue} when it says nothing either way.
     *
     * <p>Must be asked of the document that <em>declares</em> the component; see
     * {@link LinkedDocuments#documentOf(OpenAPI, String)}.
     *
     * @param openAPI      the document that declares the component
     * @param className    the component's name
     * @param defaultValue the answer when the component declares nothing
     * @return whether the component permits a null value
     */
    static boolean nullableOf(OpenAPI openAPI, String className, Boolean defaultValue) {
        Schema<?> schema = componentSchema(openAPI, className);
        if (isNullableSchema(schema)) {
            // 3.0 `nullable: true`, or a 3.1 `type: [..., "null"]` component.
            return true;
        }
        return schema == null || schema.getNullable() == null ? defaultValue : schema.getNullable();
    }

    /**
     * The default value the named component declares, or null when it declares
     * none.
     *
     * @param openAPI   the document that declares the component
     * @param className the component's name
     * @return the default, rendered as a string
     */
    static String defaultOf(OpenAPI openAPI, String className) {
        return Optional.ofNullable(componentSchema(openAPI, className))
                .map(Schema::getDefault)
                .map(Object::toString)
                .orElse(null);
    }

    /**
     * Whether the named component is an enum.
     *
     * @param openAPI   the document that declares the component
     * @param className the component's name
     * @return whether the component declares a non-empty {@code enum}
     */
    static boolean isEnumComponent(OpenAPI openAPI, String className) {
        return Optional.ofNullable(componentSchema(openAPI, className))
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
     *
     * @param ref the reference to check
     */
    static void checkReferenceIsGeneratable(String ref) {
        if (!COMPONENT_SCHEMA_REF.matcher(ref).matches()) {
            throw new IllegalStateException(String.format(
                    "Unsupported $ref '%s': hurdy-gurdy generates a class per component schema, so a "
                            + "reference must point at '#/components/schemas/<Name>' (optionally "
                            + "prefixed by another file). Move the schema into components/schemas "
                            + "and reference it from there.", ref));
        }
    }

    /**
     * The first capturing group of {@code pattern} in {@code ref}.
     *
     * @param ref     the reference to read
     * @param pattern {@link #CLASS_NAME_PATTERN} or {@link #FILE_NAME_PATTERN}
     * @return the matched group
     */
    static String extractGroup(String ref, Pattern pattern) {
        Matcher matcher = pattern.matcher(ref);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("Illegal ref:" + ref);
        }
    }
}
