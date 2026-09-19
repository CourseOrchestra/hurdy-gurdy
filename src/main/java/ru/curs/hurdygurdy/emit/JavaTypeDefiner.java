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

import ru.curs.hurdygurdy.CaseUtils;
import ru.curs.hurdygurdy.ClassCategory;
import ru.curs.hurdygurdy.GeneratorParams;
import ru.curs.hurdygurdy.JavaDtoStyle;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import static ru.curs.hurdygurdy.CaseUtils.normalizeToScreamingSnake;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.InheritedProperty;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.allPropertyKeys;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.componentSchemas;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.inheritedProperties;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.isInterfaceBase;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.localComponent;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.ownProperties;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.ownSchemaOf;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.describesObject;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.effectiveType;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.getEnumName;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.getExtendsList;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.getSubclassMapping;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isNullableSchema;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isPolymorphicInterface;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.polymorphicMembers;

/**
 * Maps a schema onto the Java type system and builds the Java DTOs.
 */
public final class JavaTypeDefiner extends TypeDefiner<TypeSpec> {
    private boolean hasJsonZonedDateTimeDeserializer;
    private final JavaClassMembers classMembers;

    /**
     * Creates a Java type definer.
     *
     * @param params             what to generate and how
     * @param typeSpecBiConsumer receives types generated as a side effect of
     *                           resolving another, such as an inline object
     */
    public JavaTypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        super(params, typeSpecBiConsumer);
        this.classMembers = JavaClassMembers.of(params.getJavaDtoStyle());
    }

    private String getInternalType(Schema<?> schema) {
        String internalType = effectiveType(schema);
        return internalType == null ? "unknown" : internalType;
    }

    /**
     * The Java type a schema maps to.
     *
     * @param schema           the schema to resolve
     * @param openAPI          the document it was written in
     * @param parent           the type being built, which receives any nested
     *                         enum the schema declares
     * @param typeNameFallback the name to give a type the schema does not name
     * @return the resolved type
     */
    public TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder parent,
                            String typeNameFallback) {
        return defineJavaType(schema, openAPI, parent, typeNameFallback, false);
    }

    /**
     * As {@link #defineJavaType(Schema, OpenAPI, TypeSpec.Builder, String)}, but aware of
     * whether {@code parent} is itself being built as a Java {@code interface} (the
     * records-mode discriminator-base path, {@link #addBaseAccessors}). A type nested
     * directly inside an {@code interface} is implicitly {@code public static}, but
     * JavaPoet requires those modifiers to be present explicitly on the nested
     * {@link TypeSpec} — otherwise it refuses to emit it (see the {@code interface}
     * {@code requires modifiers [public, static]} check). Nested inside a {@code class}
     * or {@code record}, that requirement does not apply, so the extra {@code static}
     * would be a needless (though harmless) explicit keyword; it is therefore added
     * only when {@code parentIsInterface} is set, keeping class/record-nested inline
     * enums byte-for-byte unchanged.
     */
    private TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder parent,
                                    String typeNameFallback, boolean parentIsInterface) {
        //handle anyOf <something|null>
        List<Schema> anyOf = schema.getAnyOf();
        if (anyOf != null && anyOf.size() == 2) {
            if ("null".equals(getInternalType(anyOf.get(0)))) {
                schema = anyOf.get(1);
            } else if ("null".equals(getInternalType(anyOf.get(1)))) {
                schema = anyOf.get(0);
            }
        }
        @SuppressWarnings("LocalVariableName")
        String $ref = schema.get$ref();
        if ($ref == null) {
            String internalType = getInternalType(schema);
            switch (internalType) {
                case "string":
                    if ("date".equals(schema.getFormat())) {
                        return TypeName.get(LocalDate.class);
                    } else if ("date-time".equals(schema.getFormat())) {
                        return TypeName.get(ZonedDateTime.class);
                    } else if ("uuid".equals(schema.getFormat())) {
                        return ClassName.get(UUID.class);
                    } else if ("binary".equals(schema.getFormat())) {
                        // A bare binary schema — a DTO property / JSON value — is
                        // base64-encoded in JSON, so it maps to byte[]. Binary
                        // request/response BODIES and multipart parts are position-
                        // dependent and handled by the API extractor (which maps
                        // them to Resource/InputStream/MultipartFile/FileUpload).
                        return ArrayTypeName.of(TypeName.BYTE);
                    } else if (schema.getEnum() != null) {
                        //internal enum
                        String simpleName = getEnumName(schema, typeNameFallback);
                        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(simpleName).addModifiers(Modifier.PUBLIC);
                        if (parentIsInterface) {
                            enumBuilder.addModifiers(Modifier.STATIC);
                        }
                        for (Object e : schema.getEnum()) {
                            addEnumValue(enumBuilder, e);
                        }
                        TypeSpec internalEnum = enumBuilder.build();
                        parent.addType(internalEnum);

                        return ClassName.get("", simpleName);
                    } else return ClassName.get(String.class);
                case "number":
                    if ("float".equals(schema.getFormat())) {
                        return TypeName.FLOAT.box();
                    } else {
                        return TypeName.DOUBLE.box();
                    }
                case "integer":
                    if ("int64".equals(schema.getFormat())) {
                        return TypeName.LONG.box();
                    } else {
                        return TypeName.INT.box();
                    }
                case "boolean":
                    // Unlike the number/integer cases above, a boolean stays primitive:
                    // Lombok names its accessor isFoo() rather than getFoo(), so boxing
                    // every boolean would rename accessors on existing generated code.
                    // A schema that explicitly permits null (3.0 `nullable: true`, 3.1
                    // `type: [boolean, "null"]`) must be boxed all the same — a
                    // primitive cannot hold the null the schema declares legal.
                    return isNullableSchema(schema) ? TypeName.BOOLEAN.box() : TypeName.BOOLEAN;
                case "array":
                    Schema<?> itemsSchema = schema.getItems();
                    // .box(): a primitive is not a legal type argument (List<boolean>
                    // does not exist), and JavaPoet rejects one outright.
                    return ParameterizedTypeName.get(ClassName.get(List.class),
                            defineJavaType(itemsSchema, openAPI, parent,
                                    typeNameFallback == null ? null : typeNameFallback + "Item",
                                    parentIsInterface).box());
                case "object":
                default:
                    // "object" always describes a class, empty or not. Everything
                    // reaching `default` has NO single JSON type — a 3.1 multi-type
                    // union, a `true`/`false` schema, or a schema that constrains
                    // nothing — and is a class only if it goes on to describe one.
                    // Without this test the name fallback invents an empty class
                    // from the property's own name, which compiles and means
                    // nothing; Object at least says what is actually known.
                    if (!"object".equals(internalType) && !describesObject(schema)) {
                        return ClassName.OBJECT;
                    }
                    String simpleName = schema.getTitle() == null ? typeNameFallback : schema.getTitle();
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema, openAPI));
                        return ClassName.get(String.join(".", params.getRootPackage(), "dto"),
                                simpleName);
                    } else {
                        //This means failure, in fact.
                        return ClassName.OBJECT;
                    }
            }
        } else {
            Schema<?> aliasTarget = inlinableArrayAlias($ref, openAPI);
            if (aliasTarget != null) {
                // A same-file array alias (ItemArray: type: array) is not a class;
                // inline it at the point of use (List<Item>) instead.
                return inliningAlias($ref, () ->
                        defineJavaType(aliasTarget, openAPI, parent, typeNameFallback, parentIsInterface));
            }
            return referencedClassName(openAPI, $ref);
        }
    }

    private ClassName referencedClassName(OpenAPI openAPI, String ref) {
        DTOMeta meta = getReferencedTypeInfo(openAPI, ref);
        return ClassName.get(String.join(".", meta.packageName(), "dto"), meta.className());
    }

    private void ensureJsonZonedDateTimeDeserializer() {
        if (!hasJsonZonedDateTimeDeserializer) {
            TypeSpec typeSpec =
                    TypeSpec.classBuilder("ZonedDateTimeDeserializer")
                            .superclass(ParameterizedTypeName.get(
                                    ClassName.get(JsonDeserializer.class),
                                    ClassName.get(ZonedDateTime.class)
                            ))
                            .addModifiers(Modifier.PUBLIC)
                            .addField(FieldSpec.builder(ClassName.get(DateTimeFormatter.class),
                                            "formatter")
                                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                    .initializer("$T.ISO_OFFSET_DATE_TIME", DateTimeFormatter.class)
                                    .build())
                            .addMethod(MethodSpec.methodBuilder(
                                            "deserialize")
                                    .returns(ClassName.get(ZonedDateTime.class))
                                    .addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addParameter(ParameterSpec.builder(JsonParser.class, "jsonParser").build())
                                    .addParameter(ParameterSpec.builder(DeserializationContext.class,
                                            "deserializationContext").build())
                                    .addException(IOException.class)

                                    .addStatement("String date = jsonParser.getText()")
                                    .beginControlFlow("try ")
                                    .addStatement(
                                            "return $T.parse(date, formatter)", ZonedDateTime.class)
                                    .endControlFlow()
                                    .beginControlFlow("catch ($T e)", DateTimeException.class)
                                    .beginControlFlow("try ")
                                    .addStatement("return $T.parse(date + \"Z\", formatter)", ZonedDateTime.class)
                                    .endControlFlow()
                                    .beginControlFlow("catch ($T ignored)", DateTimeException.class)
                                    .addComment("do nothing, exception thrown below")
                                    .endControlFlow()
                                    .addStatement("throw new $T(jsonParser, e.getMessage())", JsonParseException.class)
                                    .endControlFlow()
                                    .build())
                            .build();
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec);
            typeSpec =
                    TypeSpec.classBuilder("ZonedDateTimeSerializer")
                            .superclass(ParameterizedTypeName.get(
                                    ClassName.get(JsonSerializer.class),
                                    ClassName.get(ZonedDateTime.class)
                            ))
                            .addModifiers(Modifier.PUBLIC)
                            .addField(FieldSpec.builder(ClassName.get(DateTimeFormatter.class),
                                            "formatter")
                                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                    .initializer("$T.ISO_OFFSET_DATE_TIME", DateTimeFormatter.class)
                                    .build())
                            .addMethod(MethodSpec.methodBuilder(
                                            "serialize")
                                    .addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addParameter(ParameterSpec.builder(ZonedDateTime.class, "value").build())
                                    .addParameter(ParameterSpec.builder(JsonGenerator.class,
                                            "gen").build())
                                    .addParameter(ParameterSpec.builder(SerializerProvider.class,
                                            "serializers").build())
                                    .addException(IOException.class)

                                    .addStatement("gen.writeString(formatter.format(value))")
                                    .build())
                            .build();
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec);
            hasJsonZonedDateTimeDeserializer = true;
        }
    }

    @Override
    TypeSpec getDTOClass(String name, Schema<?> schema, OpenAPI openAPI) {
        // RECORDS mode needs the full (un-flattened) schema so it can see allOf
        // parents, oneOf and discriminator; route before the class-based path
        // unwraps a ComposedSchema down to its own-properties member.
        if (params.getJavaDtoStyle() == JavaDtoStyle.RECORDS) {
            return buildRecordDto(name, schema, openAPI);
        }
        // allOf inheritance. A polymorphic container (oneOf, or anyOf of two-or-more
        // $refs) has already been routed to an interface by the class-vs-interface
        // decision below; a non-polymorphic anyOf (scalars, a single $ref) has a null
        // allOf and falls through to a plain (empty) class rather than NPE-ing here.
        if (schema.getOneOf() == null && schema.getAllOf() != null) {
            ClassName baseClass = ClassName.get(Object.class);
            Schema<?> currentSchema = schema;
            Set<String> inheritedKeys = new HashSet<>();
            for (Schema<?> s : schema.getAllOf()) {
                if (s.get$ref() != null) {
                    baseClass = referencedClassName(openAPI, s.get$ref());
                    inheritedKeys.addAll(inheritedPropertyKeys(s.get$ref(), openAPI));
                } else {
                    currentSchema = s;
                }
            }
            return getDTOClass(name, currentSchema, openAPI, baseClass, inheritedKeys);
        }
        return getDTOClass(name, schema, openAPI, ClassName.get(Object.class), Set.of());
    }

    @Override
    TypeSpec getArrayAlias(String name, Schema<?> schema, OpenAPI openAPI) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(name).addModifiers(Modifier.PUBLIC);
        Schema<?> itemsSchema = schema.getItems();
        TypeName itemType = itemsSchema == null ? ClassName.OBJECT
                : defineJavaType(itemsSchema, openAPI, classBuilder, name + "Item").box();
        classBuilder.superclass(ParameterizedTypeName.get(ClassName.get(ArrayList.class), itemType));
        getExtendsList(schema).stream().map(ClassName::bestGuess).forEach(classBuilder::addSuperinterface);
        return classBuilder.build();
    }

    /**
     * All property names a class inherits through its {@code allOf} ancestor
     * chain (their own properties plus what they inherit in turn), resolved
     * within the current file only. A subclass that re-declares one of these
     * must not emit its own field: Lombok would generate a clashing
     * getter/setter that either cannot override the inherited one (narrower
     * type) or collides on erasure, neither of which compiles.
     */
    private Set<String> inheritedPropertyKeys(String ref, OpenAPI openAPI) {
        Schema<?> schema = localComponent(openAPI, ref);
        return schema == null ? Set.of() : allPropertyKeys(schema, openAPI);
    }

    private TypeSpec getDTOClass(String name, Schema<?> schema, OpenAPI openAPI, ClassName baseClass,
                                 Set<String> inheritedKeys) {
        // RECORDS mode is dispatched earlier, from the 3-arg getDTOClass, so it
        // sees the full schema rather than the unwrapped own-properties member.
        // A non-Object baseClass means this is an allOf-inheritance subtype, whose
        // equals/hashCode must fold in the parent's fields (callSuper = true).
        boolean hasParent = !ClassName.get(Object.class).equals(baseClass);
        TypeSpec.Builder classBuilder;
        if (isPolymorphicInterface(schema)) {
            classBuilder = TypeSpec.interfaceBuilder(name);
        } else {
            classBuilder = TypeSpec.classBuilder(name)
                    .superclass(baseClass);
            classMembers.decorateClass(classBuilder, hasParent);
        }
        classBuilder.addModifiers(Modifier.PUBLIC);
        if (params.isForceSnakeCaseForProperties()) {
            classBuilder.addAnnotation(AnnotationSpec.builder(JsonNaming.class).addMember("value",
                    "$T.class", ClassName.get(PropertyNamingStrategies.SnakeCaseStrategy.class)).build());
        }

        //This class is a superclass
        addDiscriminatorAnnotations(name, classBuilder, schema, openAPI);

        //This class extends interfaces
        getExtendsList(schema).stream().map(ClassName::bestGuess).forEach(classBuilder::addSuperinterface);
        polymorphicToInterface(schema, openAPI, classBuilder);
        // A class that is itself a oneOf/anyOf member implements the generated
        // polymorphic interface, so Jackson deduction polymorphism through that
        // interface works in class mode too (matches Kotlin's addInterfaces and
        // the records-mode ancestorInterfaces polymorphic branch).
        if (!isPolymorphicInterface(schema)) {
            polymorphicInterfacesOf(name, openAPI).forEach(classBuilder::addSuperinterface);
        }

        Map<String, Schema> schemaMap = schema.getProperties();
        if (schemaMap != null) {
            //Add properties
            String discriminatorProperty = schema.getDiscriminator() == null
                    ? null : schema.getDiscriminator().getPropertyName();
            for (Map.Entry<String, Schema> entry : schemaMap.entrySet()) {
                checkPropertyName(name, entry.getKey());
                // Skip the discriminator property, and any property already declared
                // by an allOf ancestor (re-declaring the latter would make Lombok emit
                // a clashing getter/setter that does not compile; the inherited field
                // and accessors are reused instead).
                if (entry.getKey().equals(discriminatorProperty)
                        || inheritedKeys.contains(entry.getKey())) {
                    continue;
                }
                addPropertyField(entry.getKey(), entry.getValue(), openAPI, classBuilder);
            }
        }

        //Dictionary support
        addAdditionalPropertiesField(schema, openAPI, classBuilder);

        // A polymorphic container is an interface and has no fields of its own.
        if (!isPolymorphicInterface(schema)) {
            classMembers.addMembers(classBuilder, hasParent);
        }
        return classBuilder.build();
    }

    private TypeSpec buildRecordDto(String name, Schema<?> schema, OpenAPI openAPI) {
        // 1) oneOf / top-level anyOf, or a discriminator base WITH subtypes ->
        // sealed interface. When a discriminator is present it wins: subtypes are
        // selected by its property value (name-based) rather than DEDUCTION, so a
        // schema declaring both oneOf and a discriminator yields a single set of
        // Jackson annotations. Pure oneOf/anyOf (no discriminator) stays DEDUCTION.
        // A discriminator base with NO subtypes would become a bare, uninstantiable
        // interface, so let it fall through to the concrete-record path below and
        // re-attach @JsonTypeInfo — mirroring the class DTO styles.
        boolean hasDiscriminatorSubtypes = schema.getDiscriminator() != null
                && !permittedSubtypes(name, schema, openAPI).isEmpty();
        if (isPolymorphicInterface(schema) || hasDiscriminatorSubtypes) {
            return buildSealedInterface(name, schema, openAPI, schema.getDiscriminator() != null);
        }
        // 3) concrete schema -> record (flatten allOf-inherited components). A
        // subtype-less discriminator base also lands here (buildConcreteRecord
        // keeps its @JsonTypeInfo and drops the discriminator property), so it is
        // instantiable instead of a bare interface.
        List<InheritedProperty> inherited = inheritedProperties(schema, openAPI);
        List<ClassName> implemented = ancestorInterfaces(name, schema, openAPI);
        return buildConcreteRecord(name, ownSchemaOf(schema), openAPI, inherited, implemented);
    }

    /**
     * The ancestor interfaces a type declares as supertypes: its {@code allOf}
     * {@code $ref} parents and any {@code oneOf} it is a member of. Used both by
     * concrete records ({@code implements}) and by nested sealed base interfaces
     * ({@code extends}), so a base's supertype clause stays consistent with the
     * outer interface's {@code permits} clause.
     */
    private List<ClassName> ancestorInterfaces(String name, Schema<?> schema, OpenAPI openAPI) {
        List<ClassName> result = new ArrayList<>();
        if (schema.getAllOf() != null) {
            for (Schema<?> s : schema.getAllOf()) {
                // Only implement an allOf parent that is itself generated as an
                // interface (a discriminator base or a oneOf container). A plain
                // object parent becomes a concrete record whose fields are
                // flattened into this record instead; `implements` against it
                // would not compile ("interface expected").
                if (s.get$ref() != null && isInterfaceBase(s.get$ref(), openAPI)) {
                    result.add(referencedClassName(openAPI, s.get$ref()));
                }
            }
        }
        // oneOf/anyOf membership: any polymorphic schema that lists this class
        result.addAll(polymorphicInterfacesOf(name, openAPI));
        return result;
    }

    /**
     * The polymorphic-interface {@link ClassName}s that the DTO named {@code
     * name} is a member of: every component schema that is a polymorphic
     * interface (a {@code oneOf}, or a top-level {@code anyOf} of $refs) whose
     * members list a {@code $ref} resolving to {@code name}. Shared by the
     * class-based path ({@link #getDTOClass}, so a class {@code implements} the
     * interface(s) it is a member of) and the records-mode {@link
     * #ancestorInterfaces} (so a record's {@code implements}/a nested base's
     * {@code extends} clause stays consistent with the outer interface's {@code
     * permits} clause).
     */
    private List<ClassName> polymorphicInterfacesOf(String name, OpenAPI openAPI) {
        List<ClassName> result = new ArrayList<>();
        componentSchemas(openAPI).forEach((schemaName, s) -> {
            for (Schema member : polymorphicMembers(s)) {
                if (member.get$ref() != null
                        && referencedClassName(openAPI, member.get$ref()).simpleName().equals(name)) {
                    result.add(ClassName.get(
                            String.join(".", params.getRootPackage(), "dto"), schemaName));
                }
            }
        });
        return result;
    }

    private TypeSpec buildConcreteRecord(String name, Schema<?> ownSchema, OpenAPI openAPI,
                                         List<InheritedProperty> inherited, List<ClassName> implemented) {
        List<InheritedProperty> components = new ArrayList<>(inherited);
        Set<String> inheritedKeys = new HashSet<>();
        inherited.forEach(c -> inheritedKeys.add(c.key()));
        for (InheritedProperty c : ownProperties(ownSchema)) {
            if (!inheritedKeys.contains(c.key())) {
                components.add(c);
            }
        }

        TypeSpec.Builder recordBuilder = TypeSpec.recordBuilder(name).addModifiers(Modifier.PUBLIC);
        if (params.isForceSnakeCaseForProperties()) {
            recordBuilder.addAnnotation(AnnotationSpec.builder(JsonNaming.class).addMember("value",
                    "$T.class", ClassName.get(PropertyNamingStrategies.SnakeCaseStrategy.class)).build());
        }
        // A subtype-less discriminator base is built as a concrete record (rather
        // than a bare, uninstantiable interface); keep its @JsonTypeInfo so Jackson
        // still reads/writes the type-id property. Bases WITH subtypes never reach
        // here (they become sealed interfaces); concrete subtypes' ownSchema is the
        // inline allOf member, whose discriminator is null, so they stay unannotated.
        if (ownSchema.getDiscriminator() != null) {
            recordBuilder.addAnnotation(discriminatorTypeInfo(ownSchema));
        }
        implemented.forEach(recordBuilder::addSuperinterface);

        MethodSpec.Builder canonical = MethodSpec.constructorBuilder();
        List<String> requiredNames = new ArrayList<>();
        for (InheritedProperty c : components) {
            String propertyName = params.isForceSnakeCaseForProperties()
                    ? CaseUtils.snakeToCamel(c.key()) : c.key();
            checkPropertyName(name, c.key());
            TypeName typeName = defineJavaType(c.schema(), openAPI, recordBuilder,
                    CaseUtils.snakeToCamel(c.key(), true));
            ParameterSpec.Builder param = ParameterSpec.builder(typeName, propertyName);
            // Record-component annotation: propagates to the field, the accessor and
            // the canonical-constructor parameter, so both hops see the pinned name.
            String jsonName = jsonNameOverride(c.key(), propertyName);
            if (jsonName != null) {
                param.addAnnotation(AnnotationSpec.builder(JsonProperty.class)
                        .addMember("value", "$S", jsonName).build());
            }
            if (typeName instanceof ClassName className && "ZonedDateTime".equals(className.simpleName())) {
                param.addAnnotation(AnnotationSpec.builder(ClassName.get(JsonDeserialize.class))
                                .addMember("using", "ZonedDateTimeDeserializer.class").build())
                        .addAnnotation(AnnotationSpec.builder(ClassName.get(JsonSerialize.class))
                                .addMember("using", "ZonedDateTimeSerializer.class").build());
                ensureJsonZonedDateTimeDeserializer();
            }
            canonical.addParameter(param.build());
            // A property that is `required` AND `nullable` must be present but may
            // hold an explicit null (OpenAPI 3.0 semantics), so it must NOT be
            // null-checked — otherwise a valid {"x":null} payload fails to
            // deserialize. Only non-nullable required components are enforced.
            if (c.required() && !isNullableType(c.schema(), openAPI)) {
                requiredNames.add(propertyName);
            }
        }
        addAdditionalPropertiesComponent(ownSchema, openAPI, recordBuilder, canonical);
        recordBuilder.recordConstructor(canonical.build());
        if (!requiredNames.isEmpty()) {
            MethodSpec.Builder compact = MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC);
            for (String req : requiredNames) {
                compact.addStatement("$T.requireNonNull($N, $S)", Objects.class, req, req);
            }
            recordBuilder.addMethod(compact.build());
        }
        return recordBuilder.build();
    }

    private void addAdditionalPropertiesComponent(Schema<?> schema, OpenAPI openAPI,
                                                  TypeSpec.Builder recordBuilder, MethodSpec.Builder canonical) {
        if (schema.getAdditionalProperties() == null) {
            return;
        }
        Object additionalProperties = schema.getAdditionalProperties();
        TypeName valueTypeName = (additionalProperties instanceof Schema<?>)
                ? defineJavaType((Schema<?>) additionalProperties, openAPI, recordBuilder, null).box()
                : TypeName.get(String.class);
        ParameterizedTypeName mapType = ParameterizedTypeName.get(ClassName.get(Map.class),
                TypeName.get(String.class), valueTypeName);
        canonical.addParameter(ParameterSpec.builder(mapType, "additionalProperties")
                .addAnnotation(JsonAnySetter.class)
                .addAnnotation(JsonAnyGetter.class)
                .build());
    }

    /**
     * The {@code @JsonTypeInfo(use = NAME, include = PROPERTY, property = ...)}
     * name-based discriminator annotation for a base schema.
     */
    private AnnotationSpec discriminatorTypeInfo(Schema<?> schema) {
        return AnnotationSpec.builder(JsonTypeInfo.class)
                .addMember("use", "$T.$L", JsonTypeInfo.Id.class, JsonTypeInfo.Id.NAME.name())
                .addMember("include", "$T.$L", JsonTypeInfo.As.class, JsonTypeInfo.As.PROPERTY.name())
                .addMember("property", "$S", schema.getDiscriminator().getPropertyName())
                .build();
    }

    /**
     * Adds the name-based discriminator Jackson annotations shared by the
     * class-based path ({@link #getDTOClass}) and the records-mode sealed
     * interface ({@link #buildSealedInterface}): {@code @JsonTypeInfo} when the
     * schema declares a discriminator, and {@code @JsonSubTypes} when it
     * declares an explicit subtype mapping. Extracted so both paths emit a
     * byte-for-byte identical block.
     */
    private void addDiscriminatorAnnotations(String name, TypeSpec.Builder builder,
                                             Schema<?> schema, OpenAPI openAPI) {
        if (schema.getDiscriminator() != null) {
            builder.addAnnotation(discriminatorTypeInfo(schema));
        }
        var subclassMapping = effectiveSubclassMapping(name, schema, openAPI);
        if (!subclassMapping.isEmpty()) {
            CodeBlock collect = subclassMapping.entrySet().stream()
                    .map(e ->
                            AnnotationSpec.builder(JsonSubTypes.Type.class)
                                    .addMember("value", "$T.class", referencedClassName(openAPI, e.getValue()))
                                    .addMember("name", "$S", e.getKey()).build())
                    .map(a -> CodeBlock.of("$L", a))
                    .collect(CodeBlock.joining(",\n", "{\n", "}"));
            builder.addAnnotation(AnnotationSpec.builder(JsonSubTypes.class)
                    .addMember("value", "$L", collect)
                    .build());
        }
    }

    /**
     * The discriminator subtype mapping to emit as {@code @JsonSubTypes}. Uses the
     * explicit {@code discriminator.mapping} when present; otherwise derives it from
     * every schema whose {@code allOf} references this base, keyed by the OpenAPI
     * implicit convention that the discriminator value is the subtype's schema name.
     *
     * <p>Java-only (does not touch the shared
     * {@link ru.curs.hurdygurdy.spec.SchemaSemantics#getSubclassMapping}, which
     * Kotlin uses). Kotlin has its own equivalent
     * {@code effectiveSubclassMapping} for the discriminator-without-mapping case,
     * and its own polymorphic-anyOf handling, so both former Kotlin gaps are now
     * closed.
     */
    private Map<String, String> effectiveSubclassMapping(String baseName, Schema<?> schema, OpenAPI openAPI) {
        Map<String, String> explicit = getSubclassMapping(schema);
        if (!explicit.isEmpty() || schema.getDiscriminator() == null) {
            // Explicit mapping wins; and a schema that is NOT itself a discriminator
            // base (e.g. an intermediate allOf child) must emit no @JsonSubTypes,
            // even though other schemas allOf-reference it.
            return explicit;
        }
        // No explicit mapping: derive {schemaName -> $ref} for every schema whose
        // allOf lists this base. Reuse the same discovery permittedSubtypes uses.
        Map<String, String> derived = new java.util.LinkedHashMap<>();
        componentSchemas(openAPI).forEach((schemaName, s) -> {
            if (s.getAllOf() != null) {
                for (Object aObj : s.getAllOf()) {
                    Schema<?> a = (Schema<?>) aObj;
                    if (a.get$ref() != null
                            && referencedClassName(openAPI, a.get$ref()).simpleName().equals(baseName)) {
                        derived.put(schemaName, "#/components/schemas/" + schemaName);
                    }
                }
            }
        });
        return derived;
    }

    /** Adds the {@code @JsonSubTypes}/{@code @JsonTypeInfo(DEDUCTION)} pair for a oneOf/anyOf sealed interface. */
    private void addOneOfDeductionAnnotations(TypeSpec.Builder ifaceBuilder, Schema<?> schema, OpenAPI openAPI) {
        CodeBlock collect = polymorphicMembers(schema).stream()
                .map(Schema::get$ref).filter(Objects::nonNull)
                .map(r -> referencedClassName(openAPI, r))
                .map(cn -> AnnotationSpec.builder(JsonSubTypes.Type.class)
                        .addMember("value", "$T.class", cn).build())
                .map(a -> CodeBlock.of("$L", a))
                .collect(CodeBlock.joining(",\n", "{\n", "}"));
        ifaceBuilder.addAnnotation(AnnotationSpec.builder(JsonSubTypes.class)
                .addMember("value", "$L", collect).build());
        ifaceBuilder.addAnnotation(AnnotationSpec.builder(JsonTypeInfo.class)
                .addMember("use", "$T.DEDUCTION", JsonTypeInfo.Id.class).build());
    }

    /** Declares abstract accessor methods for a discriminator base's own (non-discriminator) properties. */
    private void addBaseAccessors(TypeSpec.Builder ifaceBuilder, String name, Schema<?> schema, OpenAPI openAPI) {
        for (InheritedProperty c : ownProperties(schema)) {
            String propertyName = params.isForceSnakeCaseForProperties()
                    ? CaseUtils.snakeToCamel(c.key()) : c.key();
            checkPropertyName(name, c.key());
            TypeName typeName = defineJavaType(c.schema(), openAPI, ifaceBuilder,
                    CaseUtils.snakeToCamel(c.key(), true), true);
            ifaceBuilder.addMethod(MethodSpec.methodBuilder(propertyName)
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(typeName).build());
        }
    }

    private TypeSpec buildSealedInterface(String name, Schema<?> schema, OpenAPI openAPI, boolean discriminator) {
        List<ClassName> permitted = permittedSubtypes(name, schema, openAPI);
        TypeSpec.Builder ifaceBuilder = TypeSpec.interfaceBuilder(name).addModifiers(Modifier.PUBLIC);
        // A sealed interface must have at least one permitted subclass. When none
        // are visible in this file, emit a plain (non-sealed) interface instead —
        // still carrying the Jackson polymorphism annotations.
        if (!permitted.isEmpty()) {
            ifaceBuilder.addModifiers(Modifier.SEALED);
        }

        // Jackson polymorphism annotations (mirror the class-based path)
        if (discriminator) {
            addDiscriminatorAnnotations(name, ifaceBuilder, schema, openAPI);
        } else {
            addOneOfDeductionAnnotations(ifaceBuilder, schema, openAPI);
        }

        // This base may itself be nested in an outer polymorphic relation (a oneOf
        // member, or an allOf-child of a further base). Derive its supertypes from
        // the SAME helper the concrete records use, so this interface's
        // extends/implements clause and the outer interface's permits clause stay
        // consistent — otherwise javac rejects the outer "permits" clause.
        for (ClassName ancestor : ancestorInterfaces(name, schema, openAPI)) {
            ifaceBuilder.addSuperinterface(ancestor);
        }

        for (ClassName p : permitted) {
            ifaceBuilder.addPermittedSubclass(p);
        }
        if (discriminator) {
            addBaseAccessors(ifaceBuilder, name, schema, openAPI);
        }
        return ifaceBuilder.build();
    }

    /** Concrete DTO class names that a sealed base permits. */
    private List<ClassName> permittedSubtypes(String name, Schema<?> schema, OpenAPI openAPI) {
        List<ClassName> result = new ArrayList<>();
        if (isPolymorphicInterface(schema)) {
            polymorphicMembers(schema).stream().map(Schema::get$ref).filter(Objects::nonNull)
                    .map(r -> referencedClassName(openAPI, r)).forEach(result::add);
            return result;
        }
        // discriminator: subtypes are the schemas whose allOf $refs this base
        componentSchemas(openAPI).forEach((schemaName, s) -> {
            if (s.getAllOf() != null) {
                for (Object aObj : s.getAllOf()) {
                    Schema<?> a = (Schema<?>) aObj;
                    if (a.get$ref() != null
                            && referencedClassName(openAPI, a.get$ref()).simpleName().equals(name)) {
                        result.add(ClassName.get(
                                String.join(".", params.getRootPackage(), "dto"), schemaName));
                    }
                }
            }
        });
        return result;
    }

    /**
     * Adds the {@code additionalProperties} dictionary-support field, when the
     * schema declares one.
     *
     * <p>How the field is annotated is the style's business, not this method's:
     * see {@link JavaClassMembers#decorateAdditionalProperties(FieldSpec.Builder)}
     * and the note there on why {@code @JsonAnySetter} sits on the field rather
     * than on the setter.
     */
    private void addAdditionalPropertiesField(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder classBuilder) {
        if (schema.getAdditionalProperties() == null) {
            return;
        }
        Object additionalProperties = schema.getAdditionalProperties();
        TypeName valueTypeName;
        if (additionalProperties instanceof Schema<?>) {
            valueTypeName = defineJavaType((Schema<?>) additionalProperties,
                    openAPI, classBuilder, null).box();
        } else {
            valueTypeName = TypeName.get(String.class);
        }

        ParameterizedTypeName mapType = ParameterizedTypeName.get(ClassName.get(Map.class),
                TypeName.get(String.class), valueTypeName);

        FieldSpec.Builder fieldSpecBuilder = FieldSpec.builder(mapType,
                        "additionalProperties", Modifier.PRIVATE)
                .initializer("new $T<>()", HashMap.class);
        classMembers.decorateAdditionalProperties(fieldSpecBuilder);
        classBuilder.addField(fieldSpecBuilder.build());
    }

    private void addPropertyField(String key, Schema<?> value, OpenAPI openAPI,
                                  TypeSpec.Builder classBuilder) {
        TypeName typeName = defineJavaType(value, openAPI, classBuilder,
                CaseUtils.snakeToCamel(key, true));

        String propertyName =
                params.isForceSnakeCaseForProperties()
                        ? CaseUtils.snakeToCamel(key)
                        : key;

        FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                typeName,
                propertyName, Modifier.PRIVATE);
        // Pins the wire name when @JsonNaming alone cannot reproduce the spec key
        // (a leading/trailing underscore); the field-level name governs the whole
        // logical property, getter and setter included.
        String jsonName = jsonNameOverride(key, propertyName);
        if (jsonName != null) {
            fieldBuilder.addAnnotation(AnnotationSpec.builder(JsonProperty.class)
                    .addMember("value", "$S", jsonName).build());
        }
        if (typeName instanceof ClassName className && "ZonedDateTime"
                .equals(className.simpleName())) {
            fieldBuilder.addAnnotation(AnnotationSpec.builder(
                                    ClassName.get(JsonDeserialize.class))
                            .addMember("using", "ZonedDateTimeDeserializer.class").build())
                    .addAnnotation(AnnotationSpec.builder(
                                    ClassName.get(JsonSerialize.class))
                            .addMember("using", "ZonedDateTimeSerializer.class").build());
            ensureJsonZonedDateTimeDeserializer();
        }
        classBuilder.addField(fieldBuilder.build());
    }

    private void polymorphicToInterface(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder classBuilder) {
        // A discriminator, when present, selects subtypes by a property value
        // (NAME-based, emitted by addDiscriminatorAnnotations) and takes precedence
        // over oneOf/anyOf DEDUCTION. Emitting both would produce two @JsonTypeInfo
        // and two @JsonSubTypes — neither is @Repeatable, so it would not compile.
        if (isPolymorphicInterface(schema) && schema.getDiscriminator() == null) {
            var subtypesAnnotation = AnnotationSpec.builder(JsonSubTypes.class);

            final CodeBlock collect = polymorphicMembers(schema).stream()
                    .map(Schema::get$ref)
                    .filter(Objects::nonNull)
                    .map(r -> referencedClassName(openAPI, r))
                    .map(className ->
                            AnnotationSpec.builder(JsonSubTypes.Type.class)
                                    .addMember("value", "$T.class", className)
                                    .build())
                    .map(a -> CodeBlock.of("$L", a))
                    .collect(CodeBlock.joining(",\n", "{\n", "}"));
            subtypesAnnotation.addMember("value", collect);
            classBuilder.addAnnotation(subtypesAnnotation.build());
            classBuilder.addAnnotation(
                    AnnotationSpec
                            .builder(JsonTypeInfo.class)
                            .addMember("use", "$T.DEDUCTION", JsonTypeInfo.Id.class)
                            .build());
        }
    }

    private static void addEnumValue(TypeSpec.Builder classBuilder, Object value) {
        String stringValue = value.toString();
        String normalized = normalizeToScreamingSnake(stringValue);
        if (!Objects.equals(stringValue, normalized)) {
            classBuilder.addEnumConstant(
                    normalized,
                    TypeSpec.anonymousClassBuilder(CodeBlock.builder().build()).addAnnotation(
                            AnnotationSpec.builder(JsonProperty.class)
                                    .addMember("value", "$S", stringValue).build()
                    ).build()
            );
        } else {
            classBuilder.addEnumConstant(stringValue);
        }
    }

    @Override
    TypeSpec getEnum(String name, Schema<?> schema) {
        TypeSpec.Builder classBuilder = TypeSpec.enumBuilder(name).addModifiers(Modifier.PUBLIC);
        for (Object val : schema.getEnum()) {
            addEnumValue(classBuilder, val);
        }
        return classBuilder.build();
    }
}
