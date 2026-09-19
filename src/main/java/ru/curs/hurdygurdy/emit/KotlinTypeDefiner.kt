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

package ru.curs.hurdygurdy.emit

import ru.curs.hurdygurdy.ClassCategory
import ru.curs.hurdygurdy.GeneratorParams
import ru.curs.hurdygurdy.model.ArrayAliasType
import ru.curs.hurdygurdy.model.EnumType
import ru.curs.hurdygurdy.model.ObjectType
import ru.curs.hurdygurdy.model.PolymorphicType
import ru.curs.hurdygurdy.model.PropertyModel
import ru.curs.hurdygurdy.model.TypeModel
import ru.curs.hurdygurdy.model.TypeRef
import ru.curs.hurdygurdy.spec.SchemaInheritance
import ru.curs.hurdygurdy.spec.SchemaSemantics
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import ru.curs.hurdygurdy.spec.SchemaInheritance.InheritedProperty
import ru.curs.hurdygurdy.spec.SchemaSemantics.getExtendsList
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.function.BiConsumer

/**
 * Spells a described type in Kotlin, and builds the Kotlin DTOs.
 */
class KotlinTypeDefiner internal constructor(
    params: GeneratorParams,
    typeSpecBiConsumer: BiConsumer<ClassCategory?, TypeSpec?>?
) : TypeDefiner<TypeSpec?>(params, typeSpecBiConsumer) {

    private var hasJsonZonedDateTimeDeserializer = false

    /**
     * How a resolved type is spelled in Kotlin.
     *
     * A mapping table and nothing else: no schema is read here and nothing is
     * emitted. What the type *is* was decided once, language-neutrally, by
     * `TypeModelBuilder`; this table differs from the Java one exactly where the
     * two type systems do — `ByteArray` against `byte[]`, a nullable type against
     * a boxed one.
     *
     * Nullability is part of a Kotlin type, and it is applied here: a schema that
     * says nothing about null leaves the property nullable, which is the
     * generator's long-standing default for anything that may simply be left out.
     */
    fun kotlinType(ref: TypeRef): TypeName {
        val bare = when (ref.kind()) {
            TypeRef.Kind.STRING -> STRING
            TypeRef.Kind.BOOLEAN -> BOOLEAN
            TypeRef.Kind.INTEGER -> INT
            TypeRef.Kind.LONG -> LONG
            TypeRef.Kind.FLOAT -> FLOAT
            TypeRef.Kind.DOUBLE -> DOUBLE
            TypeRef.Kind.DATE -> LocalDate::class.asClassName()
            TypeRef.Kind.DATE_TIME -> ZonedDateTime::class.asClassName()
            TypeRef.Kind.UUID -> UUID::class.asClassName()
            // A bare binary schema (a DTO property / JSON value) is base64 in JSON,
            // so it maps to ByteArray. Binary request/response BODIES and multipart
            // parts are position-dependent and handled by the API extractor.
            TypeRef.Kind.BINARY -> BYTE_ARRAY
            TypeRef.Kind.ARRAY -> List::class.asClassName().parameterizedBy(kotlinType(ref.element()))
            TypeRef.Kind.ENUM -> ClassName("", ref.simpleName())
            TypeRef.Kind.REFERENCE -> ClassName(ref.packageName(), ref.simpleName())
            TypeRef.Kind.ANY -> ANY
        }
        return bare.copy(nullable = ref.nullUnion() || (ref.nullable() ?: true))
    }

    /**
     * Generates the types a resolved reference declares: an inline enum nested in
     * [parent], an inline titled object as a file of its own.
     *
     * This is the half that used to happen inside type resolution, onto a builder
     * threaded down the recursion.
     */
    fun addDeclarations(ref: TypeRef, parent: TypeSpec.Builder) {
        emitDeclarations(ref) { enumType ->
            val enumBuilder = TypeSpec.enumBuilder(enumType.name()).addModifiers(KModifier.PUBLIC)
            enumType.constants().forEach { enumBuilder.addConstant(it) }
            parent.addType(enumBuilder.build())
        }
    }

    private fun TypeSpec.Builder.addConstant(constant: EnumType.EnumConstant) {
        if (constant.wireName() == null) {
            addEnumConstant(constant.identifier())
        } else {
            addEnumConstant(
                constant.identifier(),
                TypeSpec.anonymousClassBuilder().addAnnotation(
                    AnnotationSpec.builder(JsonProperty::class)
                        .addMember("%S", constant.wireName()).build()
                ).build()
            )
        }
    }

    private fun className(ref: TypeRef): ClassName = ClassName(ref.packageName(), ref.simpleName())

    override fun getEnum(type: EnumType): TypeSpec {
        val classBuilder = TypeSpec.enumBuilder(type.name()).addModifiers(KModifier.PUBLIC)
        type.constants().forEach { classBuilder.addConstant(it) }
        return classBuilder.build()
    }

    override fun getArrayAlias(type: ArrayAliasType): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(type.name())
        val element = type.element()
        val itemType = if (element == null) ANY else {
            addDeclarations(element, classBuilder)
            kotlinType(element)
        }
        classBuilder.superclass(ClassName("kotlin.collections", "ArrayList").parameterizedBy(itemType))
        type.superInterfaces().map { ClassName.bestGuess(it) }.forEach { classBuilder.addSuperinterface(it) }
        return classBuilder.build()
    }

    override fun getDTOClass(model: TypeModel): TypeSpec {
        val base = if (model is PolymorphicType) model.base() else model as ObjectType
        val members = if (model is PolymorphicType) model.members() else emptyList()
        val schema = base.schema()
        val openAPI = base.document()
        return if (schema.oneOf == null && schema.allOf != null) {
            var baseClass: TypeName = Any::class.asClassName()
            var currentSchema = schema
            var inheritedProperties: List<InheritedProperty> = emptyList()
            for (s in schema.allOf) {
                if (s.`$ref` != null) {
                    baseClass = className(models().reference(openAPI, s.`$ref`))
                    inheritedProperties = constructorPropertiesOf(s.`$ref`, openAPI)
                } else {
                    currentSchema = s
                }
            }
            // Propagate a discriminator declared on the ComposedSchema itself onto
            // the inline `object` part, so an intermediate discriminator base (e.g.
            // `Middle` in a nested Outer->Middle->Leaf chain) becomes a sealed
            // @JsonTypeInfo base whose discriminator property is managed by Jackson
            // and excluded from the constructor — matching constructorPropertiesOf,
            // which already strips it. Without this the inline part loses the
            // discriminator, the generated intermediate keeps the discriminator
            // field as a constructor parameter, and a subclass forwards the wrong
            // argument to the super-constructor (a compile error).
            if (schema.discriminator != null && currentSchema !== schema) {
                currentSchema.discriminator = schema.discriminator
            }
            getDTOClass(base, currentSchema, members, baseClass, inheritedProperties)
        } else {
            getDTOClass(base, schema, members, Any::class.asClassName(), emptyList())
        }
    }

    /**
     * The constructor parameters the class generated for [ref] will declare, so
     * that a subclass can re-declare them as `override` and pass them to the
     * base-class constructor.
     */
    private fun constructorPropertiesOf(ref: String, openAPI: OpenAPI): List<InheritedProperty> {
        val schema = SchemaInheritance.localComponent(openAPI, ref) ?: return emptyList()
        return SchemaInheritance.flattenedProperties(schema, openAPI)
    }

    private fun getDTOClass(
        base: ObjectType,
        schema: Schema<*>,
        members: List<TypeRef>,
        baseClass: TypeName,
        inheritedProperties: List<InheritedProperty>
    ): TypeSpec {
        val name = base.name()
        val openAPI = base.document()
        val additionalProperties = base.additionalProperties()
        val polymorphic = SchemaSemantics.isPolymorphicInterface(schema)
        // Define if any schema references to us as "allOf"
        val isParent = SchemaInheritance.componentSchemas(openAPI).values.any { candidate ->
            candidate.allOf?.any { it.`$ref`?.endsWith(name) ?: false } ?: false
        }
        val classBuilder =
            (if (schema.properties.isNullOrEmpty() &&
                additionalProperties == null &&
                !polymorphic &&
                !isParent &&
                inheritedProperties.isEmpty()
            )
                TypeSpec.objectBuilder(name).superclass(baseClass)
            else if (polymorphic)
                TypeSpec.interfaceBuilder(name)
            else
                TypeSpec.classBuilder(name).superclass(baseClass))

        models().polymorphicSuperTypes(name, openAPI).forEach { classBuilder.addSuperinterface(className(it)) }
        polymorphicToInterface(schema, members, classBuilder)

        if (params.isForceSnakeCaseForProperties) {
            classBuilder.addAnnotation(
                AnnotationSpec.builder(JsonNaming::class).addMember(
                    "value = %T::class",
                    PropertyNamingStrategies.SnakeCaseStrategy::class.asClassName()
                ).build()
            )
        }

        val subclassMapping = models().subclassMapping(name, schema, openAPI)
        // A discriminator base is a sealed superclass ONLY when it actually has
        // subtypes. A discriminator base with no subtypes would otherwise become an
        // empty, uninstantiable `sealed class`, so fall back to a normal (data/open)
        // class — mirroring the Java class DTO styles — while keeping @JsonTypeInfo.
        val isSealedBase = schema.discriminator != null && subclassMapping.isNotEmpty()
        // The discriminator property is dropped from the constructor (managed by
        // Jackson), so exclude it when deciding whether a `data class` is viable: a
        // data class with zero components does not compile.
        val ownDataPropertyCount = schema.properties?.keys
            ?.count { it != schema.discriminator?.propertyName } ?: 0
        val hasDataProperties = ownDataPropertyCount > 0
            || inheritedProperties.isNotEmpty()
            || additionalProperties != null

        //This class is a superclass
        if (schema.discriminator != null) {
            if (isSealedBase) {
                classBuilder.addModifiers(KModifier.SEALED)
            }
            classBuilder.addAnnotation(
                AnnotationSpec
                    .builder(JsonTypeInfo::class)
                    .addMember("use = %T.%L", JsonTypeInfo.Id::class, JsonTypeInfo.Id.NAME.name)
                    .addMember("include = %T.%L", As::class, As.PROPERTY.name)
                    .addMember("property = %S", schema.discriminator.propertyName)
                    .build()
            )
        }
        if (!isSealedBase && hasDataProperties) {
            classBuilder.addModifiers(KModifier.DATA)
        }
        //Intermediate class, can't be data, should be open
        if (isParent && !classBuilder.modifiers.contains(KModifier.SEALED)) {
            classBuilder.addModifiers(KModifier.OPEN)
            classBuilder.modifiers.remove(KModifier.DATA)
        }

        if (subclassMapping.isNotEmpty()) {
            val mappings =
                subclassMapping
                    .map { (key, value) ->
                        AnnotationSpec.builder(JsonSubTypes.Type::class)
                            .addMember("value = %T::class", className(value))
                            .addMember("name = %S", key).build()
                    }
                    .map { CodeBlock.of("%L", it) }
            classBuilder.addAnnotation(
                AnnotationSpec.builder(JsonSubTypes::class)
                    .also { spec -> mappings.forEach { spec.addMember(it) } }
                    .build()
            )
        }

        //This class extends interfaces
        getExtendsList(schema).asSequence()
            .map(ClassName.Companion::bestGuess)
            .forEach(classBuilder::addSuperinterface)

        if ((!(schema.properties.isNullOrEmpty() && additionalProperties == null)
                    || inheritedProperties.isNotEmpty())
            && !polymorphic
        ) {
            //Add properties
            val schemaMap: Map<String, Schema<*>>? = schema.properties
            val constructorBuilder = FunSpec.constructorBuilder()
            val requiredProperties = schema.required?.toSet() ?: emptySet()

            //Re-declare properties inherited from the base class as `override` and
            //forward them to the base-class constructor. Without this a Kotlin
            //subclass of a base with required constructor properties would not
            //compile ("No value passed for parameter ...").
            for (inherited in inheritedProperties) {
                val propertyName = addConstructorProperty(
                    name, inherited.key, inherited.schema, inherited.required,
                    openAPI, classBuilder, constructorBuilder, isParent = false, isOverride = true
                )
                classBuilder.addSuperclassConstructorParameter("%N", propertyName)
            }

            val inheritedKeys = inheritedProperties.mapTo(mutableSetOf()) { it.key }.toSet()
            if (schemaMap != null) for ((key, value) in schemaMap) {
                if (schema.discriminator != null && key == schema.discriminator.propertyName) {
                    //Skip the descriminator property
                    continue
                }
                if (key in inheritedKeys) {
                    //Already re-declared above as an `override` inherited from the
                    //base class and forwarded to its constructor. Restating it here
                    //as an own property would produce a duplicate declaration.
                    continue
                }
                addConstructorProperty(
                    name, key, value, requiredProperties.contains(key),
                    openAPI, classBuilder, constructorBuilder, isParent = isParent, isOverride = false
                )
            }

            //Dictionary support
            if (additionalProperties != null) {
                addDeclarations(additionalProperties, classBuilder)
                val mapType = Map::class.asClassName().parameterizedBy(
                    STRING,
                    kotlinType(additionalProperties)
                )

                val param = ParameterSpec.builder("additionalProperties", mapType)
                    .defaultValue("HashMap()")
                    .addAnnotation(
                        AnnotationSpec.builder(JsonAnySetter::class)
                            .useSiteTarget(AnnotationSpec.UseSiteTarget.PARAM)
                            .build())
                    .addAnnotation(
                        AnnotationSpec.builder(JsonAnyGetter::class)
                            .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                            .build()
                    )
                    .build()
                constructorBuilder.addParameter(param)
                val propertySpec = PropertySpec
                    .builder("additionalProperties", mapType)
                    .initializer("additionalProperties").build()
                classBuilder.addProperty(propertySpec)
            }

            classBuilder.primaryConstructor(constructorBuilder.build())
        }
        return classBuilder.build()
    }

    /**
     * Builds a single constructor parameter and its backing property, adding both
     * to [constructorBuilder] and [classBuilder]. Returns the (possibly
     * camel-cased) property name.
     *
     * @param isParent   the declaring class is an allOf base, so the property is `open`
     * @param isOverride the property is inherited from a base class, so it is `override`
     */
    private fun addConstructorProperty(
        name: String,
        key: String,
        value: Schema<*>,
        required: Boolean,
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        constructorBuilder: FunSpec.Builder,
        isParent: Boolean,
        isOverride: Boolean
    ): String {
        models().checkPropertyName(name, key)
        val property = models().property(key, value, required, openAPI)
        addDeclarations(property.type(), classBuilder)
        // A property that may be left out is nullable whether or not its schema
        // admits an explicit null; the two are separate statements in the document
        // and are asked separately.
        val typeName = kotlinType(property.type()).copy(nullable = !required || property.nullable())
        val propertyName = property.identifier()
        val paramSpec = ParameterSpec.builder(propertyName, typeName)

        // Pins the wire name when @JsonNaming alone cannot reproduce the spec key
        // (a leading/trailing underscore). On a data-class constructor `val` the
        // annotation lands on the constructor parameter, which is where both the
        // Kotlin module's creator binding and the merged property read it from.
        property.jsonNameOverride()?.let { jsonName ->
            paramSpec.addAnnotation(
                AnnotationSpec.builder(JsonProperty::class)
                    .addMember("value = %S", jsonName)
                    .build()
            )
        }

        if (typeName is ClassName && ("ZonedDateTime" == typeName.simpleName)) {
            paramSpec.addAnnotation(
                AnnotationSpec.builder(JsonDeserialize::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FIELD)
                    .addMember("using = ZonedDateTimeDeserializer::class")
                    .build()
            )
                .addAnnotation(
                    AnnotationSpec.builder(JsonSerialize::class)
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .addMember("using = ZonedDateTimeSerializer::class")
                        .build()
                )
            ensureJsonZonedDateTimeDeserializer()
        }

        applyDefault(paramSpec, property, typeName, required)
        constructorBuilder.addParameter(paramSpec.build())

        val propertySpec = PropertySpec
            .builder(propertyName, typeName)
            .addModifiers(
                listOfNotNull(
                    // An inherited property must be `override`; a base-class
                    // property must be `open` so children can override it.
                    KModifier.OVERRIDE.takeIf { isOverride },
                    KModifier.OPEN.takeIf { isParent && !isOverride }
                )
            )
            .initializer(propertyName).build()
        classBuilder.addProperty(propertySpec)
        return propertyName
    }

    /**
     * Spells the default the model classified, or falls back to `null` for a
     * property that may simply be left out.
     */
    private fun applyDefault(
        paramSpec: ParameterSpec.Builder,
        property: PropertyModel,
        typeName: TypeName,
        required: Boolean
    ) {
        val default = property.defaultValue()
        when (default.style()) {
            //Empty list as default
            PropertyModel.DefaultValue.Style.EMPTY_LIST -> paramSpec.defaultValue("listOf()")
            //Default string value
            PropertyModel.DefaultValue.Style.STRING -> paramSpec.defaultValue("%S", default.text())
            //Default enum value
            PropertyModel.DefaultValue.Style.ENUM_CONSTANT ->
                paramSpec.defaultValue("%T.%L", typeName.copy(nullable = false), default.text())
            //"Empty object" default value
            PropertyModel.DefaultValue.Style.EMPTY_OBJECT ->
                paramSpec.defaultValue("%T()", typeName.copy(nullable = false))
            //Everything else (e.g., numbers)
            PropertyModel.DefaultValue.Style.LITERAL -> paramSpec.defaultValue("%L", default.text())
            // A structured default cannot be rendered as a Kotlin initializer
            // expression, so it is dropped — matching the Java generator, which
            // emits no initializer for object defaults — and an optional property
            // falls back to null, as one with no default at all does.
            else -> if (!required) paramSpec.defaultValue("null")
        }
    }

    private fun polymorphicToInterface(schema: Schema<*>, members: List<TypeRef>, classBuilder: TypeSpec.Builder) {
        // A discriminator, when present, selects subtypes by a property value
        // (NAME-based, emitted in getDTOClass) and takes precedence over
        // oneOf/anyOf DEDUCTION. Emitting both would produce two @JsonTypeInfo and
        // two @JsonSubTypes — neither is repeatable, so it would not compile.
        if (SchemaSemantics.isPolymorphicInterface(schema) && schema.discriminator == null) {
            val builder = AnnotationSpec.builder(JsonSubTypes::class)
            members.asSequence()
                .map { className(it) }
                .map {
                    AnnotationSpec.builder(JsonSubTypes.Type::class)
                        .addMember("%T::class", it)
                        .build()
                }
                .map { CodeBlock.of("%L", it) }
                .forEach { builder.addMember(it) }
            classBuilder.addAnnotation(builder.build())
            classBuilder.addAnnotation(
                AnnotationSpec
                    .builder(JsonTypeInfo::class)
                    .addMember("use = %T.DEDUCTION", JsonTypeInfo.Id::class)
                    .build()
            )
            classBuilder.addModifiers(KModifier.SEALED)
        }
    }

    private fun ensureJsonZonedDateTimeDeserializer() {
        if (!hasJsonZonedDateTimeDeserializer) {
            val deserTypeSpec = TypeSpec.classBuilder("ZonedDateTimeDeserializer")
                .superclass(
                    JsonDeserializer::class.asClassName()
                        .parameterizedBy(ZonedDateTime::class.asClassName())
                )
                .addProperty(
                    PropertySpec.builder(
                        "formatter",
                        DateTimeFormatter::class,
                    )
                        .addModifiers(KModifier.PRIVATE)
                        .initializer("%T.ISO_OFFSET_DATE_TIME", DateTimeFormatter::class)
                        .build()
                )
                .addFunction(
                    FunSpec.builder(
                        "deserialize"
                    )
                        .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                        .addParameter(
                            ParameterSpec.builder("jsonParser", JsonParser::class).build()
                        )
                        .addParameter(
                            ParameterSpec.builder("deserializationContext", DeserializationContext::class).build()
                        )
                        .returns(ZonedDateTime::class)
                        .addStatement("val date = jsonParser.text")
                        .beginControlFlow("try ")
                        .addStatement(
                            "return %T.parse(date, formatter)", ZonedDateTime::class
                        )
                        .endControlFlow()
                        .beginControlFlow("catch (e: %T)", DateTimeException::class)
                        .beginControlFlow("try ")
                        .addStatement("return %T.parse(date + \"Z\", formatter)", ZonedDateTime::class)
                        .endControlFlow()
                        .beginControlFlow("catch (_: %T)", DateTimeException::class)
                        .addComment("do nothing, exception thrown below")
                        .endControlFlow()
                        .addStatement("throw %T(jsonParser, e.message)", JsonParseException::class)
                        .endControlFlow()
                        .build()
                )
                .build()
            typeSpecBiConsumer.accept(ClassCategory.DTO, deserTypeSpec)
            val serTypeSpec = TypeSpec.classBuilder("ZonedDateTimeSerializer")
                .superclass(
                    JsonSerializer::class.asClassName()
                        .parameterizedBy(ZonedDateTime::class.asClassName())
                )
                .addProperty(
                    PropertySpec.builder(
                        "formatter",
                        DateTimeFormatter::class,
                    )
                        .addModifiers(KModifier.PRIVATE)
                        .initializer("%T.ISO_OFFSET_DATE_TIME", DateTimeFormatter::class)
                        .build()
                )
                .addFunction(
                    FunSpec.builder(
                        "serialize"
                    )
                        .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                        .addParameter(
                            ParameterSpec.builder("value", ZonedDateTime::class).build()
                        )
                        .addParameter(
                            ParameterSpec.builder("gen", JsonGenerator::class).build()
                        )
                        .addParameter(
                            ParameterSpec.builder("serializers", SerializerProvider::class).build()
                        )
                        .addStatement("gen.writeString(formatter.format(value))")
                        .build()
                )
                .build()
            typeSpecBiConsumer.accept(ClassCategory.DTO, serTypeSpec)
            hasJsonZonedDateTimeDeserializer = true
        }
    }

}
