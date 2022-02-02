package ru.curs.hurdygurdy

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
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
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.BiConsumer
import java.util.regex.Pattern

class KotlinTypeDefiner internal constructor(
    rootPackage: String?,
    typeSpecBiConsumer: BiConsumer<ClassCategory?, TypeSpec?>?
) : TypeDefiner<TypeSpec?>(rootPackage, typeSpecBiConsumer) {

    private var hasJsonZonedDateTimeDeserializer = false

    public override fun defineKotlinType(schema: Schema<*>, parent: TypeSpec.Builder): TypeName {
        val `$ref` = schema.`$ref`
        val result = if (`$ref` == null) {
            val internalType = schema.type
            when (internalType) {
                "string" -> if ("date" == schema.format)
                    LocalDate::class.asTypeName()
                else if ("date-time" == schema.format)
                    ZonedDateTime::class.asTypeName()
                else if ("uuid" == schema.format)
                    UUID::class.asTypeName()
                else if (schema.enum != null) {
                    //internal enum
                    val simpleName = schema.title ?: throw IllegalStateException("Inline enum schema must have a title")
                    val enumBuilder = TypeSpec.enumBuilder(simpleName).addModifiers(KModifier.PUBLIC)
                    for (e in schema.enum) {
                        enumBuilder.addEnumConstant(e.toString())
                    }
                    val internalEnum: TypeSpec = enumBuilder.build()
                    parent.addType(internalEnum)
                    ClassName("", simpleName)
                } else String::class.asTypeName()
                "number" ->
                    if ("float" == schema.format) FLOAT else DOUBLE
                "integer" -> if ("int64" == schema.format) LONG else INT
                "boolean" -> BOOLEAN
                "array" -> {
                    val itemsSchema: Schema<*> = (schema as ArraySchema).items
                    List::class.asTypeName().parameterizedBy(defineKotlinType(itemsSchema, parent)
                        .copy(nullable = false))
                }
                "object" -> {
                    val simpleName = schema.title
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema))
                        ClassName(
                            java.lang.String.join(".", rootPackage, "dto"),
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
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema))
                        ClassName(
                            java.lang.String.join(".", rootPackage, "dto"),
                            simpleName
                        )
                    } else {
                        ANY
                    }
                }
            }
        } else {
            val matcher = Pattern.compile("/([^/$]+)$").matcher(`$ref`)
            matcher.find()
            ClassName(java.lang.String.join(".", rootPackage, "dto"), matcher.group(1))
        }
        return result.copy(nullable = schema.nullable ?: true)
    }

    override fun getEnum(name: String, schema: Schema<*>): TypeSpec {
        val classBuilder = TypeSpec.enumBuilder(name).addModifiers(KModifier.PUBLIC)
        schema.enum.forEach { classBuilder.addEnumConstant(it.toString()) }
        return classBuilder.build()
    }

    override fun getDTOClass(name: String, schema: Schema<*>): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(name)
            .addAnnotation(
                AnnotationSpec.builder(JsonNaming::class).addMember(
                    "value = %T::class",
                    PropertyNamingStrategies.SnakeCaseStrategy::class.asClassName()
                ).build()
            )
            .addModifiers(KModifier.DATA)
        getExtendsList(schema).asSequence()
            .map(ClassName.Companion::bestGuess)
            .forEach(classBuilder::addSuperinterface)
        //Add properties
        val schemaMap: Map<String, Schema<*>>? = schema.properties
        val constructorBuilder = FunSpec.constructorBuilder()
        if (schemaMap != null) for ((key, value) in schemaMap) {
            check(key.matches(Regex("[a-z][a-z_0-9]*"))) {
                String.format("Property '%s' of schema '%s' is not in snake case", key, name)
            }
            val typeName = defineKotlinType(value, classBuilder)

            val propertyName = CaseUtils.snakeToCamel(key)
            val paramSpec =
                ParameterSpec.builder(propertyName, typeName)

            if (typeName is ClassName && ("ZonedDateTime" == typeName.simpleName)
            ) {
                paramSpec.addAnnotation(
                    AnnotationSpec.builder(JsonDeserialize::class)
                        .addMember("using = ZonedDateTimeDeserializer::class.java")
                        .build()
                )
                ensureJsonZonedDateTimeDeserializer()
            }

            value.default?.let {
                paramSpec.defaultValue(
                    if (typeName == String::class.asTypeName()) "%S" else "%L", it
                )
            }

            val param = paramSpec.build()
            constructorBuilder.addParameter(param)

            val propertySpec = PropertySpec
                .builder(propertyName, typeName)
                .initializer(propertyName).build()
            classBuilder.addProperty(propertySpec)
        }
        classBuilder.primaryConstructor(constructorBuilder.build())
        return classBuilder.build()
    }

    private fun ensureJsonZonedDateTimeDeserializer() {
        if (!hasJsonZonedDateTimeDeserializer) {
            val typeSpec = TypeSpec.classBuilder("ZonedDateTimeDeserializer")
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
                        .addStatement("throw %T(jsonParser, e.getMessage())", JsonParseException::class)
                        .endControlFlow()
                        .build()
                )
                .build()
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec)
            hasJsonZonedDateTimeDeserializer = true
        }
    }

}