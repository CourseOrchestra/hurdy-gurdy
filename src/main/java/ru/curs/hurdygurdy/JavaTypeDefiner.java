package ru.curs.hurdygurdy;

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
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.web.multipart.MultipartFile;

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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import static ru.curs.hurdygurdy.CaseUtils.normalizeToScreamingSnake;

public final class JavaTypeDefiner extends TypeDefiner<TypeSpec> {
    private boolean hasJsonZonedDateTimeDeserializer;

    public JavaTypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        super(params, typeSpecBiConsumer);
    }

    private String getInternalType(Schema<?> schema) {
        String internalType = schema.getType();
        if (internalType == null && schema.getTypes() != null && schema.getTypes().size() == 1) {
            internalType = schema.getTypes().iterator().next();
        }
        if (internalType == null) {
            internalType = "unknown";
        }
        return internalType;
    }

    @Override
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
                        return params.getFramework() == Framework.QUARKUS
                                ? ClassName.get("org.jboss.resteasy.reactive.multipart", "FileUpload")
                                : ClassName.get(MultipartFile.class);
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
                    return TypeName.BOOLEAN;
                case "array":
                    Schema<?> itemsSchema = schema.getItems();
                    return ParameterizedTypeName.get(ClassName.get(List.class),
                            defineJavaType(itemsSchema, openAPI, parent,
                                    typeNameFallback == null ? null : typeNameFallback + "Item",
                                    parentIsInterface));
                case "object":
                default:
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
        return ClassName.get(String.join(".", meta.getPackageName(), "dto"), meta.getClassName());
    }

    /**
     * The member subschemas of a polymorphic container — a {@code oneOf}, or a
     * top-level {@code anyOf} of two-or-more non-null {@code $ref}s (treated the
     * same way). Returns an empty list for a plain schema, a nullable
     * {@code anyOf:[X,null]}, or a single-ref anyOf. This is the single predicate
     * for "is this schema a DEDUCTION-based polymorphic interface".
     */
    private List<Schema> polymorphicMembers(Schema<?> schema) {
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return schema.getOneOf();
        }
        List<Schema> anyOf = schema.getAnyOf();
        if (anyOf != null) {
            List<Schema> refs = anyOf.stream()
                    .filter(s -> s.get$ref() != null)
                    .collect(java.util.stream.Collectors.toList());
            if (refs.size() >= 2) {
                return refs;
            }
        }
        return List.of();
    }

    private boolean isPolymorphicInterface(Schema<?> schema) {
        return !polymorphicMembers(schema).isEmpty();
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
        if (schema instanceof ComposedSchema && schema.getOneOf() == null && schema.getAllOf() != null) {
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
        if (!extractGroup(ref, FILE_NAME_PATTERN).isBlank()) {
            // Reference into another file — its schema is not visible here.
            return Set.of();
        }
        Schema<?> schema = Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(s -> s.get(extractGroup(ref, CLASS_NAME_PATTERN)))
                .orElse(null);
        return schema == null ? Set.of() : inheritedPropertyKeys(schema, openAPI);
    }

    private Set<String> inheritedPropertyKeys(Schema<?> schema, OpenAPI openAPI) {
        Set<String> keys = new HashSet<>();
        Schema<?> ownSchema = schema;
        if (schema instanceof ComposedSchema && schema.getOneOf() == null && schema.getAllOf() != null) {
            for (Schema<?> s : schema.getAllOf()) {
                if (s.get$ref() != null) {
                    keys.addAll(inheritedPropertyKeys(s.get$ref(), openAPI));
                } else {
                    ownSchema = s;
                }
            }
        }
        if (ownSchema.getProperties() != null) {
            keys.addAll(ownSchema.getProperties().keySet());
        }
        return keys;
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
            if (params.getJavaDtoStyle() == JavaDtoStyle.LOMBOK) {
                classBuilder.addAnnotation(Data.class);
                // @Data's implicit @EqualsAndHashCode is callSuper = false, so a
                // subtype would silently drop inherited fields from equals/hashCode
                // (two subtypes differing only in an inherited field would compare
                // equal). Base/standalone classes (superclass Object) keep plain
                // @Data — callSuper there would wrongly mix in Object's identity.
                if (hasParent) {
                    classBuilder.addAnnotation(AnnotationSpec.builder(EqualsAndHashCode.class)
                            .addMember("callSuper", "$L", true).build());
                }
            }
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

        if (isPojoClassSchema(schema)) {
            addPojoMembers(classBuilder, hasParent);
        }
        return classBuilder.build();
    }

    /**
     * The component schemas of {@code openAPI}, or an empty map when it declares
     * no {@code components} block (a spec with only inline/path schemas). Guards
     * the whole-document scans ({@link #polymorphicInterfacesOf}, {@link
     * #permittedSubtypes}, {@link #effectiveSubclassMapping}) against a null
     * {@code getComponents()} — {@link #polymorphicInterfacesOf} now runs for
     * every class-path DTO, so it must tolerate a component-less document.
     */
    private static Map<String, Schema> schemasOf(OpenAPI openAPI) {
        return Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .orElse(Map.of());
    }

    /** A record component carried into a record (own or flattened-inherited). */
    private record RecordComponent(String key, Schema<?> schema, boolean required) { }

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
        List<RecordComponent> inherited = inheritedComponents(schema, openAPI);
        List<ClassName> implemented = ancestorInterfaces(name, schema, openAPI);
        return buildConcreteRecord(name, currentSchemaOf(schema), openAPI, inherited, implemented);
    }

    /** The non-$ref member schema of an allOf (its own properties), else the schema itself. */
    private Schema<?> currentSchemaOf(Schema<?> schema) {
        if (schema instanceof ComposedSchema && schema.getOneOf() == null && schema.getAllOf() != null) {
            Schema<?> current = schema;
            for (Schema<?> s : schema.getAllOf()) {
                if (s.get$ref() == null) {
                    current = s;
                }
            }
            return current;
        }
        return schema;
    }

    /**
     * All components a schema inherits through its allOf $ref ancestors, first
     * (most-base) declaration wins, discriminator property excluded. Same-file
     * refs only (mirrors inheritedPropertyKeys).
     */
    private List<RecordComponent> inheritedComponents(Schema<?> schema, OpenAPI openAPI) {
        List<RecordComponent> result = new ArrayList<>();
        if (!(schema instanceof ComposedSchema) || schema.getOneOf() != null || schema.getAllOf() == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (Schema<?> s : schema.getAllOf()) {
            if (s.get$ref() != null) {
                for (RecordComponent c : componentsOfRef(s.get$ref(), openAPI)) {
                    if (seen.add(c.key())) {
                        result.add(c);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Whether a same-file {@code allOf} parent {@code $ref} is generated as an
     * interface (a discriminator base or a {@code oneOf} container) rather than
     * a concrete record. External-file refs are assumed to be bases (preserving
     * the prior always-implement behaviour), since their schema is not visible.
     */
    private boolean refIsInterfaceBase(String ref, OpenAPI openAPI) {
        if (!extractGroup(ref, FILE_NAME_PATTERN).isBlank()) {
            return true;
        }
        Schema<?> schema = Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(s -> s.get(extractGroup(ref, CLASS_NAME_PATTERN)))
                .orElse(null);
        return schema != null && (schema.getDiscriminator() != null || isPolymorphicInterface(schema));
    }

    private List<RecordComponent> componentsOfRef(String ref, OpenAPI openAPI) {
        if (!extractGroup(ref, FILE_NAME_PATTERN).isBlank()) {
            return List.of();
        }
        Schema<?> schema = Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(s -> s.get(extractGroup(ref, CLASS_NAME_PATTERN)))
                .orElse(null);
        if (schema == null) {
            return List.of();
        }
        List<RecordComponent> result = new ArrayList<>(inheritedComponents(schema, openAPI));
        result.addAll(ownComponents(currentSchemaOf(schema)));
        // Strip the ref'd schema's OWN discriminator property. When that schema is
        // itself an intermediate discriminator base (a subtype declaring its own
        // `discriminator`), currentSchemaOf unwraps its ComposedSchema to the inline
        // allOf member — which has a null discriminator — so ownComponents cannot skip
        // it there. Jackson manages that property as the @JsonTypeInfo type-id on the
        // generated base interface, so it must never be flattened into a descendant
        // record as a data component (else it is double-emitted on the wire).
        if (schema.getDiscriminator() != null) {
            String disc = schema.getDiscriminator().getPropertyName();
            result.removeIf(c -> c.key().equals(disc));
        }
        return result;
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
        if (schema instanceof ComposedSchema && schema.getAllOf() != null) {
            for (Schema<?> s : schema.getAllOf()) {
                // Only implement an allOf parent that is itself generated as an
                // interface (a discriminator base or a oneOf container). A plain
                // object parent becomes a concrete record whose fields are
                // flattened into this record instead; `implements` against it
                // would not compile ("interface expected").
                if (s.get$ref() != null && refIsInterfaceBase(s.get$ref(), openAPI)) {
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
        schemasOf(openAPI).forEach((schemaName, s) -> {
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
                                         List<RecordComponent> inherited, List<ClassName> implemented) {
        List<RecordComponent> components = new ArrayList<>(inherited);
        Set<String> inheritedKeys = new HashSet<>();
        inherited.forEach(c -> inheritedKeys.add(c.key()));
        for (RecordComponent c : ownComponents(ownSchema)) {
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
        for (RecordComponent c : components) {
            String propertyName = params.isForceSnakeCaseForProperties()
                    ? CaseUtils.snakeToCamel(c.key()) : c.key();
            checkPropertyName(name, c.key());
            TypeName typeName = defineJavaType(c.schema(), openAPI, recordBuilder,
                    CaseUtils.snakeToCamel(c.key(), true));
            ParameterSpec.Builder param = ParameterSpec.builder(typeName, propertyName);
            if (typeName instanceof ClassName className && "ZonedDateTime".equals(className.simpleName())) {
                param.addAnnotation(AnnotationSpec.builder(ClassName.get(JsonDeserialize.class))
                                .addMember("using", "ZonedDateTimeDeserializer.class").build())
                        .addAnnotation(AnnotationSpec.builder(ClassName.get(JsonSerialize.class))
                                .addMember("using", "ZonedDateTimeSerializer.class").build());
                ensureJsonZonedDateTimeDeserializer();
            }
            canonical.addParameter(param.build());
            if (c.required()) {
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
     * <p>Java-only (does not touch the shared {@link #getSubclassMapping}, which
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
        schemasOf(openAPI).forEach((schemaName, s) -> {
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
        for (RecordComponent c : ownComponents(schema)) {
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
        schemasOf(openAPI).forEach((schemaName, s) -> {
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

    /** Own (non-inherited) properties of a plain object schema, in declaration order. */
    private List<RecordComponent> ownComponents(Schema<?> schema) {
        List<RecordComponent> result = new ArrayList<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return result;
        }
        Set<String> required = schema.getRequired() == null
                ? Set.of() : new HashSet<>(schema.getRequired());
        String discriminatorProperty = schema.getDiscriminator() == null
                ? null : schema.getDiscriminator().getPropertyName();
        for (Map.Entry<String, Schema> e : properties.entrySet()) {
            if (e.getKey().equals(discriminatorProperty)) {
                continue;
            }
            result.add(new RecordComponent(e.getKey(), e.getValue(), required.contains(e.getKey())));
        }
        return result;
    }

    /**
     * Adds the {@code additionalProperties} dictionary-support field, when the
     * schema declares one. Under {@link JavaDtoStyle#LOMBOK} the field itself
     * carries {@code @JsonAnySetter} and Lombok's {@code @Getter(onMethod_ =
     * @JsonAnyGetter)}, matching historical behaviour; under {@link
     * JavaDtoStyle#POJO} the field is left unannotated because {@link
     * #addPojoMembers} adds an explicit {@code @JsonAnyGetter} getter and
     * {@code @JsonAnySetter} setter for it, and annotating the field too would
     * both leak a Lombok import into POJO output and collide with those
     * explicit accessors.
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
        if (params.getJavaDtoStyle() == JavaDtoStyle.LOMBOK) {
            fieldSpecBuilder.addAnnotation(JsonAnySetter.class)
                    .addAnnotation(AnnotationSpec.builder(Getter.class)
                            .addMember("onMethod_", "@$T", JsonAnyGetter.class).build());
        } else if (params.getJavaDtoStyle() == JavaDtoStyle.POJO) {
            // POJO: annotate the FIELD with @JsonAnySetter (like LOMBOK). Jackson
            // does NOT treat a single-Map-parameter setter that also matches the
            // setXxx bean convention as a catch-all on readValue, so the explicit
            // setter (added by addPojoMembers) must NOT carry @JsonAnySetter or
            // unknown properties are dropped on deserialization. @JsonAnyGetter
            // stays on the explicit getter.
            fieldSpecBuilder.addAnnotation(JsonAnySetter.class);
        }
        classBuilder.addField(fieldSpecBuilder.build());
    }

    /**
     * Whether {@code schema} should get explicit POJO accessors and value
     * methods: the style is {@link JavaDtoStyle#POJO} and this is a class
     * (not a {@code oneOf}/{@code anyOf} interface, which has no fields of its own).
     */
    private boolean isPojoClassSchema(Schema<?> schema) {
        return params.getJavaDtoStyle() == JavaDtoStyle.POJO
                && !isPolymorphicInterface(schema);
    }

    /**
     * Emits JavaBean getters/setters plus value-semantic equals/hashCode/toString
     * for every declared field of a POJO-style DTO. {@code equals}/{@code hashCode}
     * chain {@code super} when the class extends a generated parent
     * ({@code hasParent}), so inherited fields participate; {@code toString} covers
     * own fields only (matching Lombok {@code @Data}'s toString default).
     */
    private void addPojoMembers(TypeSpec.Builder classBuilder, boolean hasParent) {
        TypeSpec built = classBuilder.build();
        List<FieldSpec> fields = built.fieldSpecs().stream()
                .filter(f -> f.modifiers().contains(Modifier.PRIVATE)
                        && !f.modifiers().contains(Modifier.STATIC))
                .toList();
        for (FieldSpec field : fields) {
            String capital = CaseUtils.snakeToCamel(field.name(), true);
            boolean isAdditionalProperties = "additionalProperties".equals(field.name());
            MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capital)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(field.type())
                    .addStatement("return this.$N", field.name());
            if (isAdditionalProperties) {
                getter.addAnnotation(JsonAnyGetter.class);
            }
            classBuilder.addMethod(getter.build());
            MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + capital)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(field.type(), field.name())
                    .addStatement("this.$N = $N", field.name(), field.name());
            // The additionalProperties setter is deliberately left un-annotated:
            // @JsonAnySetter sits on the field (see addAdditionalPropertiesField)
            // because Jackson ignores a setXxx-named single-Map @JsonAnySetter on
            // readValue, which would drop unknown properties on deserialization.
            classBuilder.addMethod(setter.build());
        }
        addValueMethods(classBuilder, fields, hasParent);
    }

    private void addValueMethods(TypeSpec.Builder classBuilder, List<FieldSpec> fields, boolean hasParent) {
        // equals
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Object.class, "o")
                .addStatement("if (this == o) return true")
                .addStatement("if (o == null || getClass() != o.getClass()) return false");
        String cast = classBuilder.build().name();
        // Fold in the parent's fields: the getClass() check above guarantees o is
        // the same concrete type, so super's own getClass() check passes too.
        if (hasParent) {
            equals.addStatement("if (!super.equals(o)) return false");
        }
        if (fields.isEmpty()) {
            equals.addStatement("return true");
        } else {
            equals.addStatement("$L that = ($L) o", cast, cast);
            String cond = fields.stream()
                    .map(f -> String.format("$T.equals(%s, that.%s)", f.name(), f.name()))
                    .collect(java.util.stream.Collectors.joining("\n    && "));
            Object[] args = fields.stream().map(f -> Objects.class).toArray();
            equals.addStatement("return " + cond, args);
        }
        classBuilder.addMethod(equals.build());
        // hashCode: seed with super.hashCode() so inherited fields contribute.
        String names = fields.stream().map(FieldSpec::name)
                .collect(java.util.stream.Collectors.joining(", "));
        if (hasParent) {
            names = names.isEmpty() ? "super.hashCode()" : "super.hashCode(), " + names;
        }
        classBuilder.addMethod(MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addStatement("return $T.hash($L)", Objects.class, names)
                .build());
        // toString
        MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class);
        // Built as a $S/$N-interleaved format so each field value is emitted
        // via a real "+" concatenation at runtime, not baked into a single
        // escaped string literal (which $S alone over the whole body would do).
        StringBuilder format = new StringBuilder("return $S");
        List<Object> args = new ArrayList<>();
        args.add(cast + "{");
        for (int i = 0; i < fields.size(); i++) {
            FieldSpec field = fields.get(i);
            format.append(" + $S + $N");
            args.add((i == 0 ? "" : ", ") + field.name() + "=");
            args.add(field.name());
        }
        format.append(" + $S");
        args.add("}");
        toString.addStatement(format.toString(), args.toArray());
        classBuilder.addMethod(toString.build());
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
    TypeSpec getEnum(String name, Schema<?> schema, OpenAPI openAPI) {
        TypeSpec.Builder classBuilder = TypeSpec.enumBuilder(name).addModifiers(Modifier.PUBLIC);
        for (Object val : schema.getEnum()) {
            addEnumValue(classBuilder, val);
        }
        return classBuilder.build();
    }
}
