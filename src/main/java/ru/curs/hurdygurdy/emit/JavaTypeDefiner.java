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
import ru.curs.hurdygurdy.JavaDtoStyle;
import ru.curs.hurdygurdy.model.ArrayAliasType;
import ru.curs.hurdygurdy.model.EnumType;
import ru.curs.hurdygurdy.model.ObjectType;
import ru.curs.hurdygurdy.model.PolymorphicType;
import ru.curs.hurdygurdy.model.PropertyModel;
import ru.curs.hurdygurdy.model.TypeModel;
import ru.curs.hurdygurdy.model.TypeRef;
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

import static ru.curs.hurdygurdy.spec.SchemaInheritance.InheritedProperty;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.allPropertyKeys;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.inheritedProperties;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.isInterfaceBase;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.localComponent;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.ownProperties;
import static ru.curs.hurdygurdy.spec.SchemaInheritance.ownSchemaOf;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.getExtendsList;
import static ru.curs.hurdygurdy.spec.SchemaSemantics.isPolymorphicInterface;

/**
 * Spells a described type in Java, and builds the Java DTOs.
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

    /**
     * How a resolved type is spelled in Java.
     *
     * <p>A mapping table and nothing else: no schema is read here and nothing is
     * emitted. What the type <em>is</em> was decided once, language-neutrally, by
     * {@link ru.curs.hurdygurdy.model.TypeModelBuilder}; the Kotlin definer has
     * the matching table and the two differ exactly where the type systems do.
     *
     * @param ref the resolved type
     * @return the Java type name
     */
    public TypeName javaType(TypeRef ref) {
        return switch (ref.kind()) {
            case STRING -> ClassName.get(String.class);
            // Unlike the number/integer cases below, a boolean stays primitive:
            // Lombok names its accessor isFoo() rather than getFoo(), so boxing
            // every boolean would rename accessors on existing generated code. A
            // schema that explicitly permits null (3.0 `nullable: true`, 3.1
            // `type: [boolean, "null"]`) must be boxed all the same — a primitive
            // cannot hold the null the schema declares legal.
            case BOOLEAN -> Boolean.TRUE.equals(ref.nullable()) ? TypeName.BOOLEAN.box() : TypeName.BOOLEAN;
            case INTEGER -> TypeName.INT.box();
            case LONG -> TypeName.LONG.box();
            case FLOAT -> TypeName.FLOAT.box();
            case DOUBLE -> TypeName.DOUBLE.box();
            case DATE -> TypeName.get(LocalDate.class);
            case DATE_TIME -> TypeName.get(ZonedDateTime.class);
            case UUID -> ClassName.get(UUID.class);
            case BINARY -> ArrayTypeName.of(TypeName.BYTE);
            // .box(): a primitive is not a legal type argument (List<boolean>
            // does not exist), and JavaPoet rejects one outright.
            case ARRAY -> ParameterizedTypeName.get(ClassName.get(List.class), javaType(ref.element()).box());
            case ENUM -> ClassName.get("", ref.simpleName());
            case REFERENCE -> ClassName.get(ref.packageName(), ref.simpleName());
            case ANY -> ClassName.OBJECT;
        };
    }

    /**
     * Generates the types a resolved reference declares: an inline enum nested in
     * {@code parent}, an inline titled object as a file of its own.
     *
     * <p>A type nested directly inside an {@code interface} is implicitly
     * {@code public static}, but JavaPoet requires those modifiers to be present
     * explicitly on the nested {@link TypeSpec} — otherwise it refuses to emit it
     * (see the {@code interface} {@code requires modifiers [public, static]}
     * check). Nested inside a {@code class} or {@code record} that requirement
     * does not apply, so the extra {@code static} would be a needless (though
     * harmless) explicit keyword. Which of the two is being built is something
     * only the caller knows, which is why it says so here rather than having the
     * fact threaded down a resolution recursion.
     *
     * @param ref           the resolved type to look through
     * @param parent        the type being built, which receives any nested enum
     * @param intoInterface whether {@code parent} is being built as an interface
     */
    public void addDeclarations(TypeRef ref, TypeSpec.Builder parent, boolean intoInterface) {
        emitDeclarations(ref, enumType -> {
            TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(enumType.name()).addModifiers(Modifier.PUBLIC);
            if (intoInterface) {
                enumBuilder.addModifiers(Modifier.STATIC);
            }
            addEnumConstants(enumBuilder, enumType);
            parent.addType(enumBuilder.build());
        });
    }

    private ClassName className(TypeRef ref) {
        return ClassName.get(ref.packageName(), ref.simpleName());
    }

    private ClassName referencedClassName(OpenAPI openAPI, String ref) {
        return className(models().reference(openAPI, ref));
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
    TypeSpec getDTOClass(TypeModel model) {
        ObjectType object = model instanceof PolymorphicType p ? p.base() : (ObjectType) model;
        List<TypeRef> members = model instanceof PolymorphicType p ? p.members() : List.of();
        Schema<?> schema = object.schema();
        OpenAPI openAPI = object.document();
        // RECORDS mode needs the full (un-flattened) schema so it can see allOf
        // parents, oneOf and discriminator; route before the class-based path
        // unwraps a ComposedSchema down to its own-properties member.
        if (params.getJavaDtoStyle() == JavaDtoStyle.RECORDS) {
            return buildRecordDto(object);
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
            return getDTOClass(object, currentSchema, members, baseClass, inheritedKeys);
        }
        return getDTOClass(object, schema, members, ClassName.get(Object.class), Set.of());
    }

    @Override
    TypeSpec getArrayAlias(ArrayAliasType type) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(type.name()).addModifiers(Modifier.PUBLIC);
        TypeName itemType;
        if (type.element() == null) {
            itemType = ClassName.OBJECT;
        } else {
            addDeclarations(type.element(), classBuilder, false);
            itemType = javaType(type.element()).box();
        }
        classBuilder.superclass(ParameterizedTypeName.get(ClassName.get(ArrayList.class), itemType));
        type.superInterfaces().stream().map(ClassName::bestGuess).forEach(classBuilder::addSuperinterface);
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

    private TypeSpec getDTOClass(ObjectType object, Schema<?> schema, List<TypeRef> members,
                                 ClassName baseClass, Set<String> inheritedKeys) {
        // RECORDS mode is dispatched earlier, from getDTOClass(TypeModel), so it
        // sees the full schema rather than the unwrapped own-properties member.
        // A non-Object baseClass means this is an allOf-inheritance subtype, whose
        // equals/hashCode must fold in the parent's fields (callSuper = true).
        String name = object.name();
        OpenAPI openAPI = object.document();
        boolean polymorphic = isPolymorphicInterface(schema);
        boolean hasParent = !ClassName.get(Object.class).equals(baseClass);
        TypeSpec.Builder classBuilder;
        if (polymorphic) {
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
        polymorphicToInterface(schema, members, classBuilder);
        // A class that is itself a oneOf/anyOf member implements the generated
        // polymorphic interface, so Jackson deduction polymorphism through that
        // interface works in class mode too (matches Kotlin's addInterfaces and
        // the records-mode ancestorInterfaces polymorphic branch).
        if (!polymorphic) {
            models().polymorphicSuperTypes(name, openAPI).stream()
                    .map(this::className).forEach(classBuilder::addSuperinterface);
        }

        Map<String, Schema> schemaMap = schema.getProperties();
        if (schemaMap != null) {
            //Add properties
            String discriminatorProperty = schema.getDiscriminator() == null
                    ? null : schema.getDiscriminator().getPropertyName();
            for (Map.Entry<String, Schema> entry : schemaMap.entrySet()) {
                models().checkPropertyName(name, entry.getKey());
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
        addAdditionalPropertiesField(object.additionalProperties(), classBuilder);

        // A polymorphic container is an interface and has no fields of its own.
        if (!polymorphic) {
            classMembers.addMembers(classBuilder, hasParent);
        }
        return classBuilder.build();
    }

    private TypeSpec buildRecordDto(ObjectType object) {
        String name = object.name();
        Schema<?> schema = object.schema();
        OpenAPI openAPI = object.document();
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
        return buildConcreteRecord(object, ownSchemaOf(schema), inherited, implemented);
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
        models().polymorphicSuperTypes(name, openAPI).stream().map(this::className).forEach(result::add);
        return result;
    }

    private TypeSpec buildConcreteRecord(ObjectType object, Schema<?> ownSchema,
                                         List<InheritedProperty> inherited, List<ClassName> implemented) {
        String name = object.name();
        OpenAPI openAPI = object.document();
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
            models().checkPropertyName(name, c.key());
            PropertyModel property = models().property(c.key(), c.schema(), c.required(), openAPI);
            addDeclarations(property.type(), recordBuilder, false);
            TypeName typeName = javaType(property.type());
            ParameterSpec.Builder param = ParameterSpec.builder(typeName, property.identifier());
            // Record-component annotation: propagates to the field, the accessor and
            // the canonical-constructor parameter, so both hops see the pinned name.
            if (property.jsonNameOverride() != null) {
                param.addAnnotation(AnnotationSpec.builder(JsonProperty.class)
                        .addMember("value", "$S", property.jsonNameOverride()).build());
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
            if (property.required() && !property.nullable()) {
                requiredNames.add(property.identifier());
            }
        }
        addAdditionalPropertiesComponent(object.additionalProperties(), recordBuilder, canonical);
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

    private void addAdditionalPropertiesComponent(TypeRef additionalProperties,
                                                  TypeSpec.Builder recordBuilder, MethodSpec.Builder canonical) {
        if (additionalProperties == null) {
            return;
        }
        addDeclarations(additionalProperties, recordBuilder, false);
        ParameterizedTypeName mapType = ParameterizedTypeName.get(ClassName.get(Map.class),
                TypeName.get(String.class), javaType(additionalProperties).box());
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
        var subclassMapping = models().subclassMapping(name, schema, openAPI);
        if (!subclassMapping.isEmpty()) {
            CodeBlock collect = subclassMapping.entrySet().stream()
                    .map(e ->
                            AnnotationSpec.builder(JsonSubTypes.Type.class)
                                    .addMember("value", "$T.class", className(e.getValue()))
                                    .addMember("name", "$S", e.getKey()).build())
                    .map(a -> CodeBlock.of("$L", a))
                    .collect(CodeBlock.joining(",\n", "{\n", "}"));
            builder.addAnnotation(AnnotationSpec.builder(JsonSubTypes.class)
                    .addMember("value", "$L", collect)
                    .build());
        }
    }

    /** Adds the {@code @JsonSubTypes}/{@code @JsonTypeInfo(DEDUCTION)} pair for a oneOf/anyOf sealed interface. */
    private void addOneOfDeductionAnnotations(TypeSpec.Builder ifaceBuilder, List<TypeRef> members) {
        CodeBlock collect = members.stream()
                .map(this::className)
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
            models().checkPropertyName(name, c.key());
            PropertyModel property = models().property(c.key(), c.schema(), c.required(), openAPI);
            addDeclarations(property.type(), ifaceBuilder, true);
            ifaceBuilder.addMethod(MethodSpec.methodBuilder(property.identifier())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(javaType(property.type())).build());
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
            addOneOfDeductionAnnotations(ifaceBuilder, models().polymorphicMembers(schema, openAPI));
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
        if (isPolymorphicInterface(schema)) {
            return models().polymorphicMembers(schema, openAPI).stream().map(this::className).toList();
        }
        // discriminator: subtypes are the schemas whose allOf $refs this base
        return models().derivedSubtypes(name, openAPI).values().stream().map(this::className).toList();
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
    private void addAdditionalPropertiesField(TypeRef additionalProperties, TypeSpec.Builder classBuilder) {
        if (additionalProperties == null) {
            return;
        }
        addDeclarations(additionalProperties, classBuilder, false);
        ParameterizedTypeName mapType = ParameterizedTypeName.get(ClassName.get(Map.class),
                TypeName.get(String.class), javaType(additionalProperties).box());

        FieldSpec.Builder fieldSpecBuilder = FieldSpec.builder(mapType,
                        "additionalProperties", Modifier.PRIVATE)
                .initializer("new $T<>()", HashMap.class);
        classMembers.decorateAdditionalProperties(fieldSpecBuilder);
        classBuilder.addField(fieldSpecBuilder.build());
    }

    private void addPropertyField(String key, Schema<?> value, OpenAPI openAPI,
                                  TypeSpec.Builder classBuilder) {
        PropertyModel property = models().property(key, value, false, openAPI);
        addDeclarations(property.type(), classBuilder, false);
        TypeName typeName = javaType(property.type());

        FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                typeName,
                property.identifier(), Modifier.PRIVATE);
        // Pins the wire name when @JsonNaming alone cannot reproduce the spec key
        // (a leading/trailing underscore); the field-level name governs the whole
        // logical property, getter and setter included.
        if (property.jsonNameOverride() != null) {
            fieldBuilder.addAnnotation(AnnotationSpec.builder(JsonProperty.class)
                    .addMember("value", "$S", property.jsonNameOverride()).build());
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

    private void polymorphicToInterface(Schema<?> schema, List<TypeRef> members, TypeSpec.Builder classBuilder) {
        // A discriminator, when present, selects subtypes by a property value
        // (NAME-based, emitted by addDiscriminatorAnnotations) and takes precedence
        // over oneOf/anyOf DEDUCTION. Emitting both would produce two @JsonTypeInfo
        // and two @JsonSubTypes — neither is @Repeatable, so it would not compile.
        if (isPolymorphicInterface(schema) && schema.getDiscriminator() == null) {
            var subtypesAnnotation = AnnotationSpec.builder(JsonSubTypes.class);

            final CodeBlock collect = members.stream()
                    .map(this::className)
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

    private static void addEnumConstants(TypeSpec.Builder classBuilder, EnumType type) {
        for (EnumType.EnumConstant constant : type.constants()) {
            if (constant.wireName() == null) {
                classBuilder.addEnumConstant(constant.identifier());
            } else {
                classBuilder.addEnumConstant(
                        constant.identifier(),
                        TypeSpec.anonymousClassBuilder(CodeBlock.builder().build()).addAnnotation(
                                AnnotationSpec.builder(JsonProperty.class)
                                        .addMember("value", "$S", constant.wireName()).build()
                        ).build()
                );
            }
        }
    }

    @Override
    TypeSpec getEnum(EnumType type) {
        TypeSpec.Builder classBuilder = TypeSpec.enumBuilder(type.name()).addModifiers(Modifier.PUBLIC);
        addEnumConstants(classBuilder, type);
        return classBuilder.build();
    }
}
