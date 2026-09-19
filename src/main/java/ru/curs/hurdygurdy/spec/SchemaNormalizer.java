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

package ru.curs.hurdygurdy.spec;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Rewrites the handful of constructs that an OpenAPI 3.1 document can express
 * and a 3.0 one cannot into the canonical shape the generators already
 * understand, so that everything downstream of the parse can stay blind to
 * which version it is working with.
 *
 * <p>This is the single place where a JSON Schema 2020-12 spelling is
 * translated. Keeping it here rather than in the type definers is deliberate:
 * the alternative is a version test at each of the dozens of points that read a
 * schema, which is how the generator came to ignore 3.1 {@code allOf} in the
 * first place (<a
 * href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/615">issue
 * 615</a>).
 *
 * <p>Every rewrite is keyed on the <em>presence of a keyword</em>, never on the
 * document's version. A 3.0 document cannot contain {@code const},
 * {@code prefixItems} or {@code contentMediaType} in the first place, so those
 * rules are self-selecting; and where the two versions spell one idea two ways
 * — a free-form dictionary — normalizing both spellings is exactly the point.
 *
 * <p>Deliberately <em>not</em> normalized: {@code nullable}. 3.1 removed the
 * keyword and hurdy-gurdy ignores it on purpose, warning about each occurrence
 * through {@link StrayNullableCheck}; writing a canonical nullability back onto
 * the schema here would revive a keyword the specification deleted. Nullability
 * stays where it is read, in {@link SchemaSemantics#isNullableSchema(Schema)}.
 */
public final class SchemaNormalizer {

    private SchemaNormalizer() {
    }

    /**
     * Canonicalizes every schema in {@code openAPI}, in place.
     *
     * @param openAPI         the parsed document
     * @param warningListener receives a message for each rewrite that discards
     *                        information the document actually carried
     */
    public static void normalize(OpenAPI openAPI, Consumer<String> warningListener) {
        SchemaWalker.walk(openAPI, (schema, path) -> normalizeSchema(schema, path, warningListener));
    }

    private static void normalizeSchema(Schema<?> schema, String path, Consumer<String> warningListener) {
        widenArrayWithoutItemType(schema, path, warningListener);
        typeFromConst(schema);
        binaryFromContentEncoding(schema);
        dropNullEnumMember(schema);
        canonicalizeAdditionalProperties(schema);
    }

    /**
     * Gives an array whose element type is not a single schema something to
     * generate: {@code items} left out entirely (3.1 does not require it, and
     * the array then accepts anything), or a {@code prefixItems} tuple.
     *
     * <p>A tuple is widened rather than rejected because neither Java nor Kotlin
     * has a heterogeneous fixed-length list type; {@code List<Object>} is the
     * honest rendering, but it drops the per-position types the document gave,
     * so it is worth saying out loud.
     */
    private static void widenArrayWithoutItemType(Schema<?> schema, String path,
                                                  Consumer<String> warningListener) {
        if (!SchemaSemantics.isArraySchema(schema)) {
            return;
        }
        boolean tuple = schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty();
        if (!tuple && schema.getItems() != null) {
            // An ordinary array: `items` IS the element type.
            return;
        }
        if (tuple) {
            // Deliberately regardless of `items`. Alongside `prefixItems`, `items`
            // constrains only the elements AFTER the tuple prefix, so it is not the
            // common element type: taking it as one would type
            // `prefixItems: [string, integer], items: boolean` as List<Boolean> and
            // claim the first two elements are booleans.
            warningListener.accept(String.format(
                    "hurdy-gurdy: the 'prefixItems' tuple at %s has no Java/Kotlin equivalent and is "
                            + "generated as a list of any value; replace it with a plain 'items' "
                            + "element type to get something more specific",
                    path));
        }
        schema.setItems(new Schema<>());
    }

    /**
     * Gives a {@code const} the type of its own value. {@code const} replaces
     * 3.0's single-valued {@code enum} and, like it, constrains a value of an
     * ordinary type — but unlike it, the type is left implicit.
     */
    private static void typeFromConst(Schema<?> schema) {
        if (schema.getConst() == null || SchemaSemantics.effectiveType(schema) != null) {
            return;
        }
        Object value = schema.getConst();
        if (value instanceof String) {
            schema.setType("string");
        } else if (value instanceof Boolean) {
            schema.setType("boolean");
        } else if (value instanceof Integer || value instanceof Long) {
            schema.setType("integer");
            if (value instanceof Long) {
                schema.setFormat("int64");
            }
        } else if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            schema.setType("number");
        }
        // Anything else (an object or array const) keeps no type and maps to
        // Object/Any, which is what a structured constant is worth here.
    }

    /**
     * Reads 3.1's {@code contentEncoding} as the {@code format: binary} the
     * generators already handle, so a base64-encoded string becomes a byte array.
     *
     * <p>Keyed on {@code contentEncoding} and <em>not</em> on
     * {@code contentMediaType}: the two say different things.{@code contentEncoding}
     * states how the JSON string itself is encoded, which is what makes it a
     * carrier for bytes; {@code contentMediaType} only describes what the
     * <em>decoded</em> content would be. A string bearing {@code contentMediaType}
     * alone is an ordinary, unencoded string, and typing it as bytes would
     * base64-encode it on the wire — changing a valid document's meaning.
     */
    private static void binaryFromContentEncoding(Schema<?> schema) {
        if ("string".equals(SchemaSemantics.effectiveType(schema))
                && schema.getFormat() == null
                && isBase64(schema.getContentEncoding())) {
            schema.setFormat("binary");
        }
    }

    /** The RFC 4648 encodings that turn a string into bytes. */
    private static boolean isBase64(String contentEncoding) {
        return "base64".equalsIgnoreCase(contentEncoding)
                || "base64url".equalsIgnoreCase(contentEncoding);
    }

    /**
     * Removes the {@code null} member of a nullable enum, keeping the nullability
     * it was expressing.
     *
     * <p>The member itself cannot survive: an enum constant is named after its
     * value, so a null one has no name — which is where the
     * {@code NullPointerException} came from. But in a schema such as
     * {@code enum: [red, null]} the null is not decoration, it is the only
     * statement that null is permitted, and swagger-parser infers
     * {@code types: [string]} for it — nothing else records the fact. Dropping
     * the member alone would therefore quietly narrow the schema.
     *
     * <p>So when nothing else already says the schema is nullable, {@code "null"}
     * is added to the type set first. That is the canonical 3.1 spelling of
     * precisely what the member said, and it is what
     * {@link SchemaSemantics#isNullableSchema(Schema)} reads — so the nullability
     * survives in the form every other caller already understands.
     */
    private static void dropNullEnumMember(Schema<?> schema) {
        List<?> values = schema.getEnum();
        if (values == null || !values.contains(null)) {
            return;
        }
        if (!SchemaSemantics.isNullableSchema(schema)) {
            preserveNullability(schema);
        }
        List<Object> withoutNull = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                withoutNull.add(value);
            }
        }
        setEnum(schema, withoutNull.isEmpty() ? null : withoutNull);
    }

    /**
     * Records "this schema admits null" as a {@code "null"} member of the type
     * set. Does nothing when the schema declares no type at all: a lone
     * {@code "null"} type would say the value can <em>only</em> be null, which is
     * a stronger claim than the one being preserved.
     */
    private static void preserveNullability(Schema<?> schema) {
        Set<String> types = schema.getTypes();
        Set<String> withNull = new LinkedHashSet<>();
        if (types != null && !types.isEmpty()) {
            withNull.addAll(types);
        } else if (schema.getType() != null) {
            withNull.add(schema.getType());
        } else {
            return;
        }
        withNull.add("null");
        schema.setTypes(withNull);
    }

    @SuppressWarnings("unchecked")
    private static void setEnum(Schema<?> schema, List<Object> values) {
        ((Schema<Object>) schema).setEnum(values);
    }

    /**
     * Reduces the two boolean spellings of {@code additionalProperties} to the
     * schema forms that mean the same thing.
     *
     * <p>A 3.0 document writes a free-form dictionary as
     * {@code additionalProperties: true}, which the parser hands over as a plain
     * {@link Boolean}; a 3.1 document writes {@code additionalProperties: {}}
     * (or {@code true}, which its parser already turns into an empty schema).
     * Left alone, the boolean falls past every {@code instanceof Schema} test in
     * the type definers and the dictionary's values end up typed
     * {@code String} — not merely imprecise but unable to hold the values the
     * schema permits.
     *
     * <p>{@code additionalProperties: false} is the opposite statement — no
     * additional properties are allowed — and so must produce no dictionary at
     * all, rather than the {@code Map<String, String>} it used to.
     *
     * <p>Both booleans have to be recognised in both of the shapes the parser
     * produces them in: a {@link Boolean} from a 3.0 document, and a schema whose
     * {@code booleanSchemaValue} is set from a 3.1 one. Handling only the first
     * leaves a 3.1 {@code additionalProperties: false} generating the dictionary
     * its own schema forbids.
     */
    private static void canonicalizeAdditionalProperties(Schema<?> schema) {
        Boolean allowed = asBooleanSchema(schema.getAdditionalProperties());
        if (allowed != null) {
            schema.setAdditionalProperties(allowed ? new Schema<>() : null);
        }
    }

    /**
     * The value of {@code additionalProperties} when it is a boolean, however the
     * parser spelled it, or null when it is a real schema (or absent).
     */
    private static Boolean asBooleanSchema(Object additionalProperties) {
        if (additionalProperties instanceof Boolean allowed) {
            return allowed;
        }
        if (additionalProperties instanceof Schema<?> asSchema) {
            return asSchema.getBooleanSchemaValue();
        }
        return null;
    }
}
