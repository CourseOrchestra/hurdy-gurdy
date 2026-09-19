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

package ru.curs.hurdygurdy.model;

import ru.curs.hurdygurdy.CaseUtils;
import ru.curs.hurdygurdy.GeneratorParams;
import ru.curs.hurdygurdy.spec.LinkedDocuments;
import ru.curs.hurdygurdy.spec.SchemaInheritance;
import ru.curs.hurdygurdy.spec.SchemaSemantics;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static ru.curs.hurdygurdy.spec.SchemaSemantics.CLASS_NAME_PATTERN;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.FILE_NAME_PATTERN;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.checkReferenceIsGeneratable;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.describesObject;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.effectiveType;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.extractGroup;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isArraySchema;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isNullableSchema;

/**
 * Reads the component schemas of a document into the types the back ends emit.
 *
 * <p>The DTO counterpart of {@link ApiModelBuilder}, and the only place that
 * decides what a schema <em>means</em> as a type. That decision — is this a
 * date, an enum, an array, a {@code $ref}, a polymorphic base, an inline object
 * that needs a class of its own — used to be written out once per target
 * language, in two roughly forty-line trees that had already drifted apart.
 *
 * <p>What it deliberately does not decide is how any of it is <em>spelled</em>.
 * {@code byte[]} against {@code ByteArray}, a boxed {@code Integer} against a
 * nullable {@code Int}, a record against a data class: those are mapping tables,
 * they are genuinely different per language, and they stay in the back ends.
 *
 * <p>One instance serves one generation run; {@link #init(Path, Consumer)}
 * starts the next.
 */
public final class TypeModelBuilder {

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
    /** A default written as {@code {}}: an empty object, which is constructible. */
    private static final Pattern EMPTY_OBJECT_DEFAULT = Pattern.compile("\\s*\\{\\s*}\\s*");
    /** The nullable wrapper {@code anyOf: [X, null]} has exactly this many members. */
    private static final int NULL_UNION_SIZE = 2;

    private final GeneratorParams params;
    private final LinkedDocuments linkedDocuments = new LinkedDocuments();
    private final Map<String, TypeRef> externalClasses = new HashMap<>();
    private final Set<String> aliasesBeingInlined = new HashSet<>();

    /**
     * Creates a builder for one generation run.
     *
     * @param params what to generate and how
     */
    public TypeModelBuilder(GeneratorParams params) {
        this.params = params;
    }

    /**
     * Prepares the builder for one generation run, discarding what the previous
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

    // ------------------------------------------------------------------ types

    /**
     * The type generated for a component schema: an enumeration, an array alias,
     * a polymorphic base or a class, whichever the schema describes.
     *
     * @param name    the component's name
     * @param schema  the component's schema
     * @param openAPI the document it was declared in
     * @return the type to generate
     */
    public TypeModel dto(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return new EnumType(name, dtoPackage(params.getRootPackage()), constants(schema));
        }
        if (isArraySchema(schema)) {
            return arrayAlias(name, schema, openAPI);
        }
        ObjectType base = new ObjectType(name, dtoPackage(params.getRootPackage()), openAPI, schema,
                additionalPropertiesType(SchemaInheritance.ownSchemaOf(schema), openAPI));
        // The class-or-interface question is asked of the member that carries the
        // schema's own keywords: an `allOf` whose oneOf-bearing part is an inline
        // member is a base, and the composition around it is inheritance.
        Schema<?> own = SchemaInheritance.ownSchemaOf(schema);
        return SchemaSemantics.isPolymorphicInterface(own)
                ? new PolymorphicType(base, polymorphicMembers(own, openAPI))
                : base;
    }

    /**
     * The type a schema maps to at one point of use.
     *
     * @param schema           the schema to resolve
     * @param openAPI          the document it was written in
     * @param typeNameFallback the name to give a type the schema does not name,
     *                         or null when the position supplies none
     * @return the resolved type
     */
    public TypeRef resolve(Schema<?> schema, OpenAPI openAPI, String typeNameFallback) {
        Schema<?> stated = schema;
        boolean nullUnion = false;
        // `anyOf: [X, null]` is how 3.1 says "nullable X" at a point of use; the
        // type is X's and the nullability is stated out here, beside it.
        List<Schema> anyOf = schema.getAnyOf();
        if (anyOf != null && anyOf.size() == NULL_UNION_SIZE) {
            if ("null".equals(effectiveType(anyOf.get(0)))) {
                nullUnion = true;
                stated = anyOf.get(1);
            } else if ("null".equals(effectiveType(anyOf.get(1)))) {
                nullUnion = true;
                stated = anyOf.get(0);
            }
        }
        String ref = stated.get$ref();
        if (ref != null) {
            Schema<?> aliasTarget = inlinableArrayAlias(ref, openAPI);
            if (aliasTarget == null) {
                return reference(openAPI, ref);
            }
            // A same-file array alias (ItemArray: type: array) is not a class;
            // inline it at the point of use (List<Item>) instead.
            Schema<?> target = aliasTarget;
            return inliningAlias(ref, () -> resolve(target, openAPI, typeNameFallback));
        }
        return declared(stated, openAPI, typeNameFallback, nullUnion);
    }

    /**
     * The resolution of a schema that is neither a {@code $ref} nor a nullable
     * wrapper around one: the decision tree itself.
     */
    private TypeRef declared(Schema<?> schema, OpenAPI openAPI, String typeNameFallback, boolean nullUnion) {
        Boolean nullable = statedNullability(schema);
        String internalType = effectiveType(schema);
        if (internalType == null) {
            internalType = "unknown";
        }
        return switch (internalType) {
            case "string" -> string(schema, nullable, nullUnion, typeNameFallback);
            case "number" -> scalar("float".equals(schema.getFormat())
                    ? TypeRef.Kind.FLOAT : TypeRef.Kind.DOUBLE, nullable, nullUnion);
            case "integer" -> scalar("int64".equals(schema.getFormat())
                    ? TypeRef.Kind.LONG : TypeRef.Kind.INTEGER, nullable, nullUnion);
            case "boolean" -> scalar(TypeRef.Kind.BOOLEAN, nullable, nullUnion);
            case "array" -> array(schema, openAPI, typeNameFallback, nullable, nullUnion);
            default -> object(schema, openAPI, typeNameFallback, nullable, nullUnion, internalType);
        };
    }

    private TypeRef string(Schema<?> schema, Boolean nullable, boolean nullUnion, String typeNameFallback) {
        String format = schema.getFormat();
        if ("date".equals(format)) {
            return scalar(TypeRef.Kind.DATE, nullable, nullUnion);
        }
        if ("date-time".equals(format)) {
            return scalar(TypeRef.Kind.DATE_TIME, nullable, nullUnion);
        }
        if ("uuid".equals(format)) {
            return scalar(TypeRef.Kind.UUID, nullable, nullUnion);
        }
        if ("binary".equals(format)) {
            // A bare binary schema — a DTO property / JSON value — is base64-encoded
            // in JSON, so it maps to a byte array. Binary request/response BODIES and
            // multipart parts are position-dependent and handled by the API
            // extractor, which maps them to Resource/InputStream/MultipartFile/FileUpload.
            return scalar(TypeRef.Kind.BINARY, nullable, nullUnion);
        }
        if (schema.getEnum() == null) {
            return scalar(TypeRef.Kind.STRING, nullable, nullUnion);
        }
        String simpleName = SchemaSemantics.getEnumName(schema, typeNameFallback);
        EnumType declaration = new EnumType(simpleName, "", constants(schema));
        return new TypeRef(TypeRef.Kind.ENUM, nullable, nullUnion, null, declaration, "", simpleName);
    }

    private TypeRef array(Schema<?> schema, OpenAPI openAPI, String typeNameFallback,
                          Boolean nullable, boolean nullUnion) {
        Schema<?> items = schema.getItems();
        TypeRef element = resolve(items, openAPI,
                        typeNameFallback == null ? null : typeNameFallback + "Item")
                // Whether a list may hold nulls is the element schema's business,
                // decided through any $ref of its own rather than at this site.
                .withNullable(isNullableType(items, openAPI));
        return new TypeRef(TypeRef.Kind.ARRAY, nullable, nullUnion, element, null, null, null);
    }

    /**
     * The resolution of a schema that describes an object, or of one that
     * describes nothing a type can express.
     *
     * <p>{@code type: object} always names a class, empty or not. Anything
     * reaching here without a single JSON type — a 3.1 multi-type union, a
     * {@code true}/{@code false} schema, a schema that constrains nothing — is a
     * class only if it goes on to describe one; otherwise the name fallback would
     * invent an empty class out of whatever the position happened to be called,
     * which compiles and means nothing.
     */
    private TypeRef object(Schema<?> schema, OpenAPI openAPI, String typeNameFallback,
                           Boolean nullable, boolean nullUnion, String internalType) {
        if (!"object".equals(internalType) && !describesObject(schema)) {
            return scalar(TypeRef.Kind.ANY, nullable, nullUnion);
        }
        String simpleName = schema.getTitle() == null ? typeNameFallback : schema.getTitle();
        if (simpleName == null) {
            //This means failure, in fact.
            return scalar(TypeRef.Kind.ANY, nullable, nullUnion);
        }
        return new TypeRef(TypeRef.Kind.REFERENCE, nullable, nullUnion, null,
                dto(simpleName, schema, openAPI), dtoPackage(params.getRootPackage()), simpleName);
    }

    private ArrayAliasType arrayAlias(String name, Schema<?> schema, OpenAPI openAPI) {
        Schema<?> items = schema.getItems();
        TypeRef element = items == null ? null
                : resolve(items, openAPI, name + "Item").withNullable(isNullableType(items, openAPI));
        return new ArrayAliasType(name, dtoPackage(params.getRootPackage()), element,
                SchemaSemantics.getExtendsList(schema));
    }

    /**
     * The value type of the dictionary a schema declares with
     * {@code additionalProperties}, or null when it declares none.
     *
     * <p>A boolean {@code additionalProperties} never reaches here as a boolean:
     * {@code SchemaNormalizer} has already turned {@code true} into an empty
     * schema and {@code false} into no dictionary at all. Anything else that is
     * not a schema is a document the parser could not make sense of, and a
     * dictionary of strings is what it has always been given.
     */
    private TypeRef additionalPropertiesType(Schema<?> schema, OpenAPI openAPI) {
        Object additionalProperties = schema.getAdditionalProperties();
        if (additionalProperties == null) {
            return null;
        }
        return additionalProperties instanceof Schema<?> valueSchema
                ? resolve(valueSchema, openAPI, null)
                : new TypeRef(TypeRef.Kind.STRING, Boolean.FALSE, false, null, null, null, null);
    }

    private static TypeRef scalar(TypeRef.Kind kind, Boolean nullable, boolean nullUnion) {
        return new TypeRef(kind, nullable, nullUnion, null, null, null, null);
    }

    private static List<EnumType.EnumConstant> constants(Schema<?> schema) {
        List<EnumType.EnumConstant> result = new ArrayList<>();
        for (Object value : schema.getEnum()) {
            String stated = value.toString();
            String identifier = CaseUtils.normalizeToScreamingSnake(stated);
            result.add(new EnumType.EnumConstant(identifier,
                    identifier.equals(stated) ? null : stated));
        }
        return result;
    }

    /**
     * What a schema says about null on its own account: {@code TRUE} when it
     * admits null in any of the three spellings, {@code FALSE} when 3.0's
     * {@code nullable: false} forbids it, null when it says nothing.
     */
    private static Boolean statedNullability(Schema<?> schema) {
        return isNullableSchema(schema) ? Boolean.TRUE : schema.getNullable();
    }

    // ------------------------------------------------------------- properties

    /**
     * One property of a generated type, described the same way for every back
     * end.
     *
     * @param key      the property name as written in the specification
     * @param schema   the property's schema
     * @param required whether the declaring object lists it as required
     * @param openAPI  the document it was declared in
     * @return the property
     */
    public PropertyModel property(String key, Schema<?> schema, boolean required, OpenAPI openAPI) {
        String identifier = params.isForceSnakeCaseForProperties() ? CaseUtils.snakeToCamel(key) : key;
        TypeRef type = resolve(schema, openAPI, CaseUtils.snakeToCamel(key, true));
        return new PropertyModel(key, identifier, jsonNameOverride(key, identifier), type,
                required, isNullableType(schema, openAPI), propertyDefault(schema, type, openAPI));
    }

    /**
     * The default a property declares, classified by what an initializer for it
     * would have to be.
     *
     * <p>A {@code $ref} property is answered by the component it names, and — in
     * the current document rather than the declaring one, and ignoring any
     * sibling {@code default}, because OpenAPI 3.0 ignores keywords written
     * beside a {@code $ref}. That is a narrower rule than
     * {@link #effectiveDefault}, which answers the same question for a
     * <em>parameter</em>; the two are deliberately left as they are rather than
     * merged, because merging them is a change of output.
     */
    private PropertyModel.DefaultValue propertyDefault(Schema<?> schema, TypeRef type, OpenAPI openAPI) {
        String ref = schema.get$ref();
        Object stated = ref == null
                ? schema.getDefault()
                : SchemaSemantics.defaultOf(openAPI, extractGroup(ref, CLASS_NAME_PATTERN));
        if (stated == null) {
            return PropertyModel.DefaultValue.NONE;
        }
        String text = stated.toString();
        PropertyModel.DefaultValue.Style style;
        if (isArraySchema(schema)) {
            style = PropertyModel.DefaultValue.Style.EMPTY_LIST;
        } else if (type.kind() == TypeRef.Kind.STRING) {
            style = PropertyModel.DefaultValue.Style.STRING;
        } else if (ref == null) {
            style = PropertyModel.DefaultValue.Style.LITERAL;
        } else if (SchemaSemantics.isEnumComponent(openAPI, extractGroup(ref, CLASS_NAME_PATTERN))) {
            style = PropertyModel.DefaultValue.Style.ENUM_CONSTANT;
        } else if (EMPTY_OBJECT_DEFAULT.matcher(text).matches()) {
            style = PropertyModel.DefaultValue.Style.EMPTY_OBJECT;
        } else {
            style = PropertyModel.DefaultValue.Style.STRUCTURED;
        }
        return new PropertyModel.DefaultValue(style, text);
    }

    /**
     * Rejects a property name that {@code forceSnakeCaseForProperties} cannot
     * round-trip.
     *
     * @param name         the schema the property belongs to, for the message
     * @param propertyName the property name as written in the specification
     */
    public void checkPropertyName(String name, String propertyName) {
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
    public String jsonNameOverride(String key, String propertyName) {
        if (!params.isForceSnakeCaseForProperties()) {
            // No @JsonNaming is emitted, and the identifier IS the spec key.
            return null;
        }
        return SNAKE_CASE_STRATEGY.translate(propertyName).equals(key) ? null : key;
    }

    // ---------------------------------------------------------- polymorphism

    /**
     * The types a polymorphic base may actually be: the members of its
     * {@code oneOf}, or of a top-level {@code anyOf} of two or more
     * {@code $ref}s. Empty for a schema that is not a base at all.
     *
     * @param schema  the schema to read
     * @param openAPI the document it was declared in
     * @return the member types, in declaration order
     */
    public List<TypeRef> polymorphicMembers(Schema<?> schema, OpenAPI openAPI) {
        List<TypeRef> result = new ArrayList<>();
        for (Schema<?> member : SchemaSemantics.polymorphicMembers(schema)) {
            if (member.get$ref() != null) {
                result.add(reference(openAPI, member.get$ref()));
            }
        }
        return result;
    }

    /**
     * The polymorphic bases that name {@code name} as one of their members, and
     * whose generated interface the type therefore implements.
     *
     * @param name    the simple name of the generated type
     * @param openAPI the document it was declared in
     * @return the interfaces to implement, in component order
     */
    public List<TypeRef> polymorphicSuperTypes(String name, OpenAPI openAPI) {
        List<TypeRef> result = new ArrayList<>();
        SchemaInheritance.componentSchemas(openAPI).forEach((schemaName, schema) -> {
            for (TypeRef member : polymorphicMembers(schema, openAPI)) {
                if (member.simpleName().equals(name)) {
                    result.add(referenceTo(dtoPackage(params.getRootPackage()), schemaName, false));
                }
            }
        });
        return result;
    }

    /**
     * The discriminator subtype mapping to emit: the explicit
     * {@code discriminator.mapping} when the base declares one, otherwise the
     * subtypes derived from every schema whose {@code allOf} references this
     * base, keyed by the OpenAPI convention that the discriminator value is the
     * subtype's schema name.
     *
     * <p>The "not a base at all" guard is load-bearing: an intermediate
     * {@code allOf} child that other schemas reference must emit no
     * {@code @JsonSubTypes} of its own.
     *
     * @param baseName the simple name of the generated base
     * @param schema   the base's schema
     * @param openAPI  the document it was declared in
     * @return discriminator value to subtype, in declaration order
     */
    public Map<String, TypeRef> subclassMapping(String baseName, Schema<?> schema, OpenAPI openAPI) {
        Map<String, String> explicit = SchemaSemantics.getSubclassMapping(schema);
        if (!explicit.isEmpty()) {
            Map<String, TypeRef> result = new LinkedHashMap<>();
            explicit.forEach((value, ref) -> result.put(value, reference(openAPI, ref)));
            return result;
        }
        return schema.getDiscriminator() == null ? Map.of() : derivedSubtypes(baseName, openAPI);
    }

    /**
     * The generated types whose {@code allOf} names {@code baseName} as a parent,
     * keyed by their schema name — which is the discriminator value the OpenAPI
     * convention gives them.
     *
     * @param baseName the simple name of the generated base
     * @param openAPI  the document to search
     * @return schema name to subtype, in component order
     */
    public Map<String, TypeRef> derivedSubtypes(String baseName, OpenAPI openAPI) {
        Map<String, TypeRef> derived = new LinkedHashMap<>();
        SchemaInheritance.componentSchemas(openAPI).forEach((schemaName, schema) -> {
            if (schema.getAllOf() != null) {
                for (Object parentObject : schema.getAllOf()) {
                    Schema<?> parent = (Schema<?>) parentObject;
                    if (parent.get$ref() != null
                            && reference(openAPI, parent.get$ref()).simpleName().equals(baseName)) {
                        derived.put(schemaName,
                                referenceTo(dtoPackage(params.getRootPackage()), schemaName, false));
                    }
                }
            }
        });
        return derived;
    }

    // ------------------------------------------------------------- references

    /**
     * The type a {@code $ref} names, wherever it was declared.
     *
     * @param currentOpenAPI the document the reference was written in
     * @param ref            the reference to resolve
     * @return the named type
     */
    public TypeRef reference(OpenAPI currentOpenAPI, String ref) {
        checkReferenceIsGeneratable(ref);
        String fileName = extractGroup(ref, FILE_NAME_PATTERN);
        String className = extractGroup(ref, CLASS_NAME_PATTERN);
        if (fileName.isBlank()) {
            return referenceTo(dtoPackage(params.getRootPackage()), className,
                    SchemaSemantics.nullableOf(currentOpenAPI, className, true));
        }
        return externalClasses.computeIfAbsent(ref, f -> {
            OpenAPI openAPI = linkedDocuments.documentOf(currentOpenAPI, ref);
            String packageName = Optional.ofNullable(openAPI.getExtensions())
                    .map(e -> e.get("x-package"))
                    .map(String.class::cast)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("x-package not defined for externally linked file %s ",
                                    linkedDocuments.sourceFile().resolveSibling(fileName))));
            return referenceTo(dtoPackage(packageName), className,
                    SchemaSemantics.nullableOf(openAPI, className, true));
        });
    }

    private static TypeRef referenceTo(String packageName, String simpleName, boolean nullable) {
        return new TypeRef(TypeRef.Kind.REFERENCE, nullable, false, null, null, packageName, simpleName);
    }

    private static String dtoPackage(String rootPackage) {
        return String.join(".", rootPackage, "dto");
    }

    // -------------------------------------------------------- generator policy

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
    public Schema<?> inlinableArrayAlias(String ref, OpenAPI openAPI) {
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
    public <R> R inliningAlias(String ref, Supplier<R> action) {
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
     * <p>The single answer to that question, for every position — property,
     * array element, parameter, request body, response. Asking it a second way
     * is what
     * <a href="https://github.com/CourseOrchestra/hurdy-gurdy/issues/620">issue 620</a>
     * was.
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
    public boolean isNullableType(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) {
            return false;
        }
        String ref = schema.get$ref();
        if (ref == null) {
            return isNullableSchema(schema);
        }
        return SchemaSemantics.nullableOf(linkedDocuments.documentOf(openAPI, ref),
                extractGroup(ref, CLASS_NAME_PATTERN), false);
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
    public String effectiveDefault(Schema<?> schema, OpenAPI openAPI) {
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
                        linkedDocuments.documentOf(openAPI, ref), extractGroup(ref, CLASS_NAME_PATTERN));
    }
}
