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
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springframework.web.multipart.MultipartFile
import ru.curs.hurdygurdy.CaseUtils.normalizeToCamel
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
                    "binary" == schema.format -> MultipartFile::class.asTypeName()
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


    override fun getEnum(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        val classBuilder = TypeSpec.enumBuilder(name).addModifiers(KModifier.PUBLIC)
        schema.enum.forEach { classBuilder.addEnumValue(it) }
        return classBuilder.build()
    }

    override fun getDTOClass(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        return if (schema is ComposedSchema && schema.oneOf == null) {
            var baseClass: TypeName = Any::class.asClassName()
            var currentSchema = schema
            for (s in schema.allOf) {
                if (s.`$ref` != null) {
                    baseClass = referencedTypeName(s.`$ref`, openAPI).copy(nullable = false)
                } else {
                    currentSchema = s
                }
            }
            getDTOClass(name, currentSchema, openAPI, baseClass)
        } else {
            getDTOClass(name, schema, openAPI, Any::class.asClassName())
        }
    }

    private fun getDTOClass(name: String, schema: Schema<*>, openAPI: OpenAPI, baseClass: TypeName): TypeSpec {
        // Define if any schema references to us as "allOf"
        val isParent = openAPI.components.schemas.any { (_, schema) ->
            schema.allOf?.any { it.`$ref`?.endsWith(name) ?: false } ?: false
        }
        val classBuilder =
            (if (schema.properties.isNullOrEmpty() &&
                schema.additionalProperties == null &&
                schema.oneOf.isNullOrEmpty() &&
                !isParent
            )
                TypeSpec.objectBuilder(normalizeToCamel(name)).superclass(baseClass)
            else if (!schema.oneOf.isNullOrEmpty())
                TypeSpec.interfaceBuilder(normalizeToCamel(name))
            else
                TypeSpec.classBuilder(normalizeToCamel(name)).superclass(baseClass))

        addInterfaces(openAPI, name, classBuilder)
        oneOfToInterface(schema, openAPI, classBuilder)

        if (params.isForceSnakeCaseForProperties) {
            classBuilder.addAnnotation(
                AnnotationSpec.builder(JsonNaming::class).addMember(
                    "value = %T::class",
                    PropertyNamingStrategies.SnakeCaseStrategy::class.asClassName()
                ).build()
            )
        }

        //This class is a superclass
        if (schema.discriminator != null) {
            classBuilder.addModifiers(KModifier.SEALED)
            classBuilder.addAnnotation(
                AnnotationSpec
                    .builder(JsonTypeInfo::class)
                    .addMember("use = %T.%L", JsonTypeInfo.Id::class, JsonTypeInfo.Id.NAME.name)
                    .addMember("include = %T.%L", As::class, As.PROPERTY.name)
                    .addMember("property = %S", schema.discriminator.propertyName)
                    .build()
            )
        } else if (!(schema.properties.isNullOrEmpty() && schema.additionalProperties == null)) {
            classBuilder.addModifiers(KModifier.DATA)
        }
        //Intermediate class, can't be data, should be open
        if (isParent && !classBuilder.modifiers.contains(KModifier.SEALED)) {
            classBuilder.addModifiers(KModifier.OPEN)
            classBuilder.modifiers.remove(KModifier.DATA)
        }

        val subclassMapping = getSubclassMapping(schema)
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

        if (!(schema.properties.isNullOrEmpty() && schema.additionalProperties == null) && schema.oneOf == null) {
            //Add properties
            val schemaMap: Map<String, Schema<*>>? = schema.properties
            val constructorBuilder = FunSpec.constructorBuilder()
            val requiredProperties = schema.required?.toSet() ?: emptySet()
            if (schemaMap != null) for ((key, value) in schemaMap) {
                checkPropertyName(name, key)
                if (schema.discriminator != null && key == schema.discriminator.propertyName) {
                    //Skip the descriminator property
                    continue
                }
                val required = requiredProperties.contains(key)
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

                if (typeName is ClassName && ("ZonedDateTime" == typeName.simpleName)
                ) {
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
                    if (value.type == "array") {
                        //Empty list as default
                        paramSpec.defaultValue("listOf()")
                    } else if (typeName.copy(nullable = false) == String::class.asTypeName()) {
                        //Default string value
                        paramSpec.defaultValue("%S", default.toString())
                    } else if (value.`$ref` != null && isEnum(
                            openAPI,
                            extractGroup(value.`$ref`, CLASS_NAME_PATTERN)
                        )
                    ) {
                        //Default enum value
                        paramSpec.defaultValue("%T.%L", typeName.copy(nullable = false), default.toString())
                    } else if (value.`$ref` != null && default.toString().matches(Regex("\\s*\\{\\s*}\\s*"))) {
                        //"Empty object" default value
                        paramSpec.defaultValue("%T()", typeName.copy(nullable = false))
                    } else {
                        //Everything else (e.g., numbers)
                        paramSpec.defaultValue("%L", default.toString())
                    }
                } else if (!required) {
                    paramSpec.defaultValue("null")
                }

                val param = paramSpec.build()
                constructorBuilder.addParameter(param)

                val propertySpec = PropertySpec
                    .builder(propertyName, typeName)
                    // If we're parent children can override even types!
                    .addModifiers(listOfNotNull(KModifier.OPEN.takeIf { isParent }))
                    .initializer(propertyName).build()
                classBuilder.addProperty(propertySpec)
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
                    .addAnnotation(JsonAnySetter::class)
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


    private fun oneOfToInterface(schema: Schema<*>, openAPI: OpenAPI, classBuilder: TypeSpec.Builder) {
        if (schema.oneOf != null) {
            val builder = AnnotationSpec.builder(JsonSubTypes::class)
            schema.oneOf.asSequence()
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
                if (schema.oneOf != null) {
                    val interfaceName = ClassName(
                        java.lang.String.join(".", params.rootPackage, "dto"),
                        schemaName
                    ).copy(nullable = false)

                    for (s in schema.oneOf) {
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
