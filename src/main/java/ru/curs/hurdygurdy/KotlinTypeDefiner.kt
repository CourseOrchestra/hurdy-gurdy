package ru.curs.hurdygurdy

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
import com.squareup.kotlinpoet.joinToCode
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springframework.web.multipart.MultipartFile
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

    public override fun defineKotlinType(
        schema: Schema<*>, openAPI: OpenAPI,
        parent: TypeSpec.Builder, typeNameFallback: String?
    ): TypeName {
        val `$ref` = schema.`$ref`
        val result = if (`$ref` == null) {
            val internalType = schema.type
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
                            enumBuilder.addEnumConstant(e.toString())
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
                    val itemsSchema: Schema<*> = (schema as ArraySchema).items
                    List::class.asTypeName().parameterizedBy(
                        defineKotlinType(itemsSchema, openAPI, parent, typeNameFallback?.plus("Item"))
                            .copy(nullable = (itemsSchema.nullable ?: false))
                    )
                }
                "object" -> {
                    val simpleName = schema.title
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
            return referencedTypeName(`$ref`, openAPI)
        }
        return result.copy(nullable = schema.nullable ?: true)
    }

    private fun referencedTypeName(
        `$ref`: String,
        openAPI: OpenAPI
    ): TypeName {
        val meta = getReferencedTypeInfo(openAPI, `$ref`)
        return ClassName(java.lang.String.join(".", meta.packageName, "dto"), meta.className)
            .copy(nullable = meta.isNullable)
    }


    override fun getEnum(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        val classBuilder = TypeSpec.enumBuilder(name).addModifiers(KModifier.PUBLIC)
        schema.enum.forEach { classBuilder.addEnumConstant(it.toString()) }
        return classBuilder.build()
    }

    override fun getDTOClass(name: String, schema: Schema<*>, openAPI: OpenAPI): TypeSpec {
        return if (schema is ComposedSchema) {
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
        val classBuilder =
            (if (schema.properties.isNullOrEmpty())
                TypeSpec.objectBuilder(name)
            else
                TypeSpec.classBuilder(name))
                .superclass(baseClass)

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
        } else if (!schema.properties.isNullOrEmpty()) {
            classBuilder.addModifiers(KModifier.DATA)
        }

        val subclassMapping = getSubclassMapping(schema)
        if (subclassMapping.isNotEmpty()) {
            val mappings =
                subclassMapping
                    .map { (key, value) ->
                        AnnotationSpec.builder(JsonSubTypes.Type::class)
                            .addMember("value = %T::class", referencedTypeName(value, openAPI))
                            .addMember("name = %S", key).build()
                    }
                    .map { CodeBlock.of("%L", it) }.joinToCode(",\n")
            classBuilder.addAnnotation(
                AnnotationSpec.builder(JsonSubTypes::class)
                    .addMember(mappings)
                    .build()
            )
        }

        //This class extends interfaces
        getExtendsList(schema).asSequence()
            .map(ClassName.Companion::bestGuess)
            .forEach(classBuilder::addSuperinterface)

        if (!schema.properties.isNullOrEmpty()) {
            //Add properties
            val schemaMap: Map<String, Schema<*>>? = schema.properties
            val constructorBuilder = FunSpec.constructorBuilder()
            if (schemaMap != null) for ((key, value) in schemaMap) {
                checkPropertyName(name, key)
                if (schema.discriminator != null && key == schema.discriminator.propertyName) {
                    //Skip the descriminator property
                    continue
                }
                val typeName = defineKotlinType(
                    value, openAPI, classBuilder,
                    CaseUtils.snakeToCamel(key, true)
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

                val default = value.default
                if (default != null) {
                    if (value.type == "array") {
                        paramSpec.defaultValue("listOf()")
                    } else {
                        paramSpec.defaultValue(
                            if (typeName == String::class.asTypeName()) "%S" else "%L", default
                        )
                    }
                } else {
                    if (typeName.isNullable) {
                        paramSpec.defaultValue("null")
                    }
                }

                val param = paramSpec.build()
                constructorBuilder.addParameter(param)

                val propertySpec = PropertySpec
                    .builder(propertyName, typeName)
                    .initializer(propertyName).build()
                classBuilder.addProperty(propertySpec)
            }
            classBuilder.primaryConstructor(constructorBuilder.build())
        }
        return classBuilder.build()
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