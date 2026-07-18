package ru.curs.hurdygurdy

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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springframework.web.multipart.MultipartFile
import ru.curs.hurdygurdy.CaseUtils.normalizeToScreamingSnake
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.BiConsumer

class KotlinTypeDefiner internal constructor(
    params: GeneratorParams,
    typeSpecBiConsumer: BiConsumer<ClassCategory?, TypeSpec?>?
) : TypeDefiner<TypeSpec?>(params, typeSpecBiConsumer) {

    private var hasJsonZonedDateTimeDeserializer = false

    private fun TypeSpec.Builder.addEnumValue(e: Any?) {
        val stringValue = e.toString()
        val normalized = normalizeToScreamingSnake(stringValue)
        if (stringValue != normalized) {
            addEnumConstant(
                normalized,
                TypeSpec.anonymousClassBuilder().addAnnotation(
                    AnnotationSpec.builder(JsonProperty::class)
                        .addMember("%S", stringValue).build()
                ).build()
            )
        } else {
            addEnumConstant(stringValue)
        }
    }

    private fun Schema<*>.getInternalType() = type ?: types?.singleOrNull()

    public override fun defineKotlinType(
        outerSchema: Schema<*>, openAPI: OpenAPI,
        parent: TypeSpec.Builder, typeNameFallback: String?, nullableOverride: Boolean?
    ): TypeName {
        //handle anyOf <something|null>
        val anyOf = outerSchema.anyOf
        var nullableByAnyOf: Boolean? = null
        val schema = if (anyOf != null && anyOf.size == 2) {
            if ("null" == anyOf[0].getInternalType()) {
                nullableByAnyOf = true
                anyOf[1]
            } else if ("null" == anyOf[1].getInternalType()) {
                nullableByAnyOf = true
                anyOf[0]
            } else outerSchema
        } else outerSchema
        val `$ref` = schema.`$ref`
        val result = if (`$ref` == null) {
            val internalType = schema.getInternalType()
            when (internalType) {
                "string" -> when {
                    "date" == schema.format -> LocalDate::class.asTypeName()
                    "date-time" == schema.format -> ZonedDateTime::class.asTypeName()
                    "uuid" == schema.format -> UUID::class.asTypeName()
                    "binary" == schema.format -> if (params.framework == Framework.QUARKUS)
                        ClassName("org.jboss.resteasy.reactive.multipart", "FileUpload")
                    else MultipartFile::class.asTypeName()
                    schema.enum != null -> {
                        //internal enum
                        val simpleName = getEnumName(schema, typeNameFallback)
                        val enumBuilder = TypeSpec.enumBuilder(simpleName).addModifiers(KModifier.PUBLIC)
                        for (e in schema.enum) {
                            enumBuilder.addEnumValue(e)
                        }
                        val internalEnum: TypeSpec = enumBuilder.build()
                        parent.addType(internalEnum)
                        ClassName("", simpleName)
                    }

                    else -> String::class.asTypeName()
                }

                "number" ->
                    if ("float" == schema.format) FLOAT else DOUBLE

                "integer" -> if ("int64" == schema.format) LONG else INT
                "boolean" -> BOOLEAN
                "array" -> {
                    val itemsSchema: Schema<*> = schema.items
                    List::class.asTypeName().parameterizedBy(
                        defineKotlinType(itemsSchema, openAPI, parent, typeNameFallback?.plus("Item"), null)
                            .copy(nullable = (itemsSchema.nullable ?: false))
                    )
                }

                "object" -> {
                    val simpleName = schema.title ?: typeNameFallback
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema, openAPI))
                        ClassName(
                            java.lang.String.join(".", params.rootPackage, "dto"),
                            simpleName
                        )
                    } else {
                        //This means failure, in fact.
                        ANY
                    }
                }

                else -> {
                    val simpleName = schema.title
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema, openAPI))
                        ClassName(
                            java.lang.String.join(".", params.rootPackage, "dto"),
                            simpleName
                        )
                    } else {
                        ANY
                    }
                }
            }
        } else {
            val aliasTarget = inlinableArrayAlias(`$ref`, openAPI)
            if (aliasTarget != null) {
                // A same-file array alias (ItemArray: type: array) is not a class;
                // inline it at the point of use (List<Item>) instead.
                return inliningAlias(`$ref`) {
                    defineKotlinType(aliasTarget, openAPI, parent, typeNameFallback, nullableOverride)
                }
            }
            return referencedTypeName(`$ref`, openAPI, nullableOverride)
        }
        return result.copy(nullable = nullableOverride ?: nullableByAnyOf ?: schema.nullable ?: true)
    }

    private fun referencedTypeName(
        `$ref`: String,
        openAPI: OpenAPI,
        nullableOverride: Boolean? = null,
    ): TypeName {
        val meta = getReferencedTypeInfo(openAPI, `$ref`)
        return ClassName(java.lang.String.join(".", meta.packageName, "dto"), meta.className)
            .copy(nullable = nullableOverride ?: meta.isNullable)
    }

    /**
     * The subtype mapping used to emit `@JsonSubTypes` for a discriminator base.
     *
     * Prefers the explicit `discriminator.mapping`; when the base declares a
     * discriminator but no explicit mapping, derives
     * `{schemaName -> "#/components/schemas/" + schemaName}` for every component
     * schema whose `allOf` references this base, following the implicit convention
     * that the discriminator value is the subtype's schema name.
     *
     * The `schema.discriminator == null` guard is essential: a plain allOf
     * intermediate that other schemas reference must emit no `@JsonSubTypes`.
     * Kotlin-only; does not touch the shared [getSubclassMapping] used by Java.
     */
    private fun effectiveSubclassMapping(
        name: String, schema: Schema<*>, openAPI: OpenAPI
    ): Map<String, String> {
        val explicit = getSubclassMapping(schema).toMap()
        if (explicit.isNotEmpty() || schema.discriminator == null) {
            return explicit
        }
        val derived = LinkedHashMap<String, String>()
        openAPI.components?.schemas?.forEach { (schemaName, s) ->
            s.allOf?.forEach { a ->
                if (a.`$ref` != null
                    && (referencedTypeName(a.`$ref`, openAPI).copy(nullable = false) as ClassName).simpleName == name
                ) {
                    derived[schemaName] = "#/components/schemas/$schemaName"
                }
            }
        }
        return derived
    }


    override fun getEnum(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        val classBuilder = TypeSpec.enumBuilder(name).addModifiers(KModifier.PUBLIC)
        schema.enum.forEach { classBuilder.addEnumValue(it) }
        return classBuilder.build()
    }

    /** A constructor property carried over from a base class (an allOf parent). */
    private data class InheritedProperty(val key: String, val schema: Schema<*>, val required: Boolean)

    /**
     * The member subschemas of a polymorphic container — a `oneOf`, or a top-level
     * `anyOf` of two-or-more non-null `$ref`s (treated the same way). Returns an
     * empty list for a plain schema, a nullable `anyOf:[X,null]`, or a single-ref
     * anyOf. Single predicate for "is this schema a DEDUCTION-based polymorphic
     * interface".
     */
    private fun polymorphicMembers(schema: Schema<*>): List<Schema<*>> {
        if (!schema.oneOf.isNullOrEmpty()) return schema.oneOf
        val refs = schema.anyOf?.filter { it.`$ref` != null } ?: emptyList()
        return if (refs.size >= 2) refs else emptyList()
    }

    private fun isPolymorphicInterface(schema: Schema<*>): Boolean = polymorphicMembers(schema).isNotEmpty()

    override fun getDTOClass(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        return if (schema is ComposedSchema && schema.oneOf == null && schema.allOf != null) {
            var baseClass: TypeName = Any::class.asClassName()
            var currentSchema = schema
            var inheritedProperties: List<InheritedProperty> = emptyList()
            for (s in schema.allOf) {
                if (s.`$ref` != null) {
                    baseClass = referencedTypeName(s.`$ref`, openAPI).copy(nullable = false)
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
            getDTOClass(name, currentSchema, openAPI, baseClass, inheritedProperties)
        } else {
            getDTOClass(name, schema, openAPI, Any::class.asClassName(), emptyList())
        }
    }

    override fun getArrayAlias(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(name)
        val itemsSchema: Schema<*>? = schema.items
        val itemType = if (itemsSchema == null) ANY
        else defineKotlinType(itemsSchema, openAPI, classBuilder, name + "Item", null)
            .copy(nullable = (itemsSchema.nullable ?: false))
        classBuilder.superclass(ClassName("kotlin.collections", "ArrayList").parameterizedBy(itemType))
        getExtendsList(schema).map { ClassName.bestGuess(it) }.forEach { classBuilder.addSuperinterface(it) }
        return classBuilder.build()
    }

    /**
     * The constructor parameters that the class generated for [ref] will declare,
     * in declaration order (its own allOf-inherited parameters first, then its
     * own properties), excluding the discriminator property. Used so that a
     * subclass can re-declare the inherited parameters as `override` and pass
     * them to the base-class constructor. Only resolves same-file references.
     */
    private fun constructorPropertiesOf(ref: String, openAPI: OpenAPI): List<InheritedProperty> {
        val schema = resolveLocalSchema(ref, openAPI) ?: return emptyList()
        return constructorPropertiesOf(schema, openAPI)
    }

    private fun constructorPropertiesOf(schema: Schema<*>, openAPI: OpenAPI): List<InheritedProperty> {
        val result = mutableListOf<InheritedProperty>()
        // A property can be re-declared at several levels of an allOf chain (the
        // YouTrack spec, for example, restates inherited fields on every subtype).
        // Keep only the first (most-base) declaration by key so the generated
        // constructor does not carry duplicate parameters. First-wins also keeps
        // the parameter order aligned with the base-class constructor, which is
        // what a subclass forwards its super-constructor arguments to.
        val seen = mutableSetOf<String>()
        fun add(property: InheritedProperty) {
            if (seen.add(property.key)) {
                result.add(property)
            }
        }
        var ownSchema: Schema<*> = schema
        if (schema is ComposedSchema && schema.oneOf == null && schema.allOf != null) {
            for (s in schema.allOf) {
                if (s.`$ref` != null) {
                    constructorPropertiesOf(s.`$ref`, openAPI).forEach(::add)
                } else {
                    ownSchema = s
                }
            }
        }
        val discriminatorProperty = schema.discriminator?.propertyName
        val required = ownSchema.required?.toSet() ?: emptySet()
        ownSchema.properties?.forEach { (key, value) ->
            if (key != discriminatorProperty) {
                add(InheritedProperty(key, value, required.contains(key)))
            }
        }
        return result
    }

    private fun resolveLocalSchema(ref: String, openAPI: OpenAPI): Schema<*>? {
        if (extractGroup(ref, FILE_NAME_PATTERN).isNotBlank()) {
            // Reference into another file — we cannot see its schema here, so we
            // leave inherited-property synthesis to that file's own generation.
            return null
        }
        return openAPI.components?.schemas?.get(extractGroup(ref, CLASS_NAME_PATTERN))
    }

    private fun getDTOClass(
        name: String,
        schema: Schema<*>,
        openAPI: OpenAPI,
        baseClass: TypeName,
        inheritedProperties: List<InheritedProperty>
    ): TypeSpec {
        // Define if any schema references to us as "allOf"
        val isParent = openAPI.components.schemas.any { (_, schema) ->
            schema.allOf?.any { it.`$ref`?.endsWith(name) ?: false } ?: false
        }
        val classBuilder =
            (if (schema.properties.isNullOrEmpty() &&
                schema.additionalProperties == null &&
                !isPolymorphicInterface(schema) &&
                !isParent &&
                inheritedProperties.isEmpty()
            )
                TypeSpec.objectBuilder(name).superclass(baseClass)
            else if (isPolymorphicInterface(schema))
                TypeSpec.interfaceBuilder(name)
            else
                TypeSpec.classBuilder(name).superclass(baseClass))

        addInterfaces(openAPI, name, classBuilder)
        polymorphicToInterface(schema, openAPI, classBuilder)

        if (params.isForceSnakeCaseForProperties) {
            classBuilder.addAnnotation(
                AnnotationSpec.builder(JsonNaming::class).addMember(
                    "value = %T::class",
                    PropertyNamingStrategies.SnakeCaseStrategy::class.asClassName()
                ).build()
            )
        }

        val subclassMapping = effectiveSubclassMapping(name, schema, openAPI)
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
            || schema.additionalProperties != null

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
                            .addMember("value = %T::class", referencedTypeName(value, openAPI).copy(nullable = false))
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

        if ((!(schema.properties.isNullOrEmpty() && schema.additionalProperties == null)
                    || inheritedProperties.isNotEmpty())
            && !isPolymorphicInterface(schema)
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
            if (schema.additionalProperties != null) {
                val additionalProperties = schema.additionalProperties
                val valueTypeName = if (additionalProperties is Schema<*>) {
                    defineKotlinType(
                        additionalProperties, openAPI, classBuilder, null, null
                    )
                } else {
                    String::class.asTypeName()
                }

                val mapType = Map::class.asClassName().parameterizedBy(
                    String::class.asTypeName(),
                    valueTypeName
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
        checkPropertyName(name, key)
        val nullable = if (value.`$ref` == null) {
            value.nullable == true
        } else {
            getNullable(openAPI, extractGroup(value.`$ref`, CLASS_NAME_PATTERN), false)
        }
        val typeName = defineKotlinType(
            value, openAPI, classBuilder,
            CaseUtils.snakeToCamel(key, true),
            !required || nullable
        )

        val propertyName =
            if (params.isForceSnakeCaseForProperties) {
                CaseUtils.snakeToCamel(key)
            } else {
                key
            }
        val paramSpec =
            ParameterSpec.builder(propertyName, typeName)

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

        val default = if (value.`$ref` == null) {
            value.default
        } else getDefault(openAPI, extractGroup(value.`$ref`, CLASS_NAME_PATTERN))
        if (default != null) {
            when {
                value.type == "array" -> {
                    //Empty list as default
                    paramSpec.defaultValue("listOf()")
                }

                typeName.copy(nullable = false) == String::class.asTypeName() -> {
                    //Default string value
                    paramSpec.defaultValue("%S", default.toString())
                }

                value.`$ref` != null && isEnum(
                    openAPI,
                    extractGroup(value.`$ref`, CLASS_NAME_PATTERN)
                )
                    -> {
                    //Default enum value
                    paramSpec.defaultValue("%T.%L", typeName.copy(nullable = false), default.toString())
                }

                value.`$ref` != null && default.toString().matches(Regex("\\s*\\{\\s*}\\s*")) -> {
                    //"Empty object" default value
                    paramSpec.defaultValue("%T()", typeName.copy(nullable = false))
                }

                value.`$ref` != null -> {
                    // A $ref default that is neither an enum constant nor an empty
                    // object (handled above) is a structured object default, e.g.
                    // {order: SIMILARITY, limit: 10}. It cannot be rendered as a
                    // Kotlin initializer expression, so drop it — matching the Java
                    // generator, which emits no initializer for object defaults —
                    // and fall back to null for an optional property.
                    if (!required) {
                        paramSpec.defaultValue("null")
                    }
                }

                else -> {
                    //Everything else (e.g., numbers)
                    paramSpec.defaultValue("%L", default.toString())
                }
            }
        } else if (!required) {
            paramSpec.defaultValue("null")
        }

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


    private fun polymorphicToInterface(schema: Schema<*>, openAPI: OpenAPI, classBuilder: TypeSpec.Builder) {
        // A discriminator, when present, selects subtypes by a property value
        // (NAME-based, emitted in getDTOClass) and takes precedence over
        // oneOf/anyOf DEDUCTION. Emitting both would produce two @JsonTypeInfo and
        // two @JsonSubTypes — neither is repeatable, so it would not compile.
        if (isPolymorphicInterface(schema) && schema.discriminator == null) {
            val builder = AnnotationSpec.builder(JsonSubTypes::class)
            polymorphicMembers(schema).asSequence()
                .map { it.`$ref` }
                .filterNotNull()
                .map { referencedTypeName(it, openAPI) }
                .map { it.copy(nullable = false) }
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

    private fun addInterfaces(openAPI: OpenAPI, name: String, classBuilder: TypeSpec.Builder) {
        openAPI.components.schemas.forEach { schemaName, schema ->
            if (schema is ComposedSchema) {
                if (isPolymorphicInterface(schema)) {
                    val interfaceName = ClassName(
                        java.lang.String.join(".", params.rootPackage, "dto"),
                        schemaName
                    ).copy(nullable = false)

                    for (s in polymorphicMembers(schema)) {
                        if (s.`$ref` != null) {
                            val typeName = referencedTypeName(s.`$ref`, openAPI).copy(nullable = true)
                            val className = (typeName as ClassName).simpleName
                            if (className == name) {
                                classBuilder.addSuperinterface(interfaceName)
                            }
                        }
                    }
                }
            }
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
