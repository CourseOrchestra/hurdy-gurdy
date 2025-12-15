package ru.curs.hurdygurdy

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import org.springframework.web.bind.annotation.*
import java.util.*
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequest
import ru.curs.hurdygurdy.CaseUtils.normalizeToCamel
import kotlin.reflect.KClass
import kotlin.streams.asSequence

class KotlinAPIExtractor(
    typeDefiner: TypeDefiner<TypeSpec>,
    params: GeneratorParams
) :
    APIExtractor<TypeSpec, TypeSpec.Builder>(
        typeDefiner,
        params,
        { TypeSpec.interfaceBuilder(normalizeToCamel(it)) },
        TypeSpec.Builder::build
    ) {

    public override fun buildMethod(
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        stringPathItemEntry: Map.Entry<String, PathItem>,
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        operationId: String,
        generateResponseParameter: Boolean
    ) {
        val methodBuilder = FunSpec
            .builder(operationId)
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.key)?.let(methodBuilder::addAnnotation)
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnKotlinType(operationEntry.value, openAPI, classBuilder))
        Optional.ofNullable(operationEntry.value.requestBody)
            .map { obj: RequestBody -> obj.content }
            .stream().asSequence()
            .flatMap { getContentType(it, openAPI, classBuilder) }
            .forEach { paramSpec: RequestPartParams ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        paramSpec.name,
                        paramSpec.typeName
                    ).addAnnotation(paramSpec.annotation).build()
                )
            }

        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "path".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.snakeToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, openAPI, classBuilder, null, null),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(PathVariable::class)
                                .addMember("name = %S", parameter.name).build()
                        )
                        .build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "query".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                val builder = AnnotationSpec.builder(RequestParam::class)
                    .addMember("required = %L", parameter.required)
                    .addMember("name = %S", parameter.name)
                parameter.schema?.default?.let { builder.addMember("defaultValue = %S", it.toString()) }
                val annotationSpec = builder.build()
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.snakeToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, openAPI, classBuilder, null, null),
                    )
                        .addAnnotation(
                            annotationSpec
                        ).build()
                )
            }
        JavaAPIExtractor.getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "header".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.kebabToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, openAPI, classBuilder, null, null),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(
                                RequestHeader::class
                            ).addMember("required = %L", parameter.required)
                                .addMember("name = %S", parameter.name).build()
                        ).build()
                )
            }
        if (generateResponseParameter) {
            val includeRequest = Optional.ofNullable(operationEntry.value.extensions)
                .map { it["x-include-request"] }
                .map {
                    when (it) {
                        is Boolean -> it
                        is String -> it.toBoolean()
                        else -> false
                    }
                }.orElse(false)
            if (includeRequest) {
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        "request",
                        HttpServletRequest::class,
                    ).build()
                )
            }
            methodBuilder.addParameter(
                ParameterSpec.builder(
                    "response",
                    HttpServletResponse::class,
                ).build()
            )
        }
        classBuilder.addFunction(methodBuilder.build())
    }

    private fun getControllerMethodAnnotationSpec(
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        path: String
    ): AnnotationSpec? {
        val annotationClass: KClass<out Annotation>? = when (operationEntry.key) {
            PathItem.HttpMethod.GET -> GetMapping::class
            PathItem.HttpMethod.POST -> PostMapping::class
            PathItem.HttpMethod.PUT -> PutMapping::class
            PathItem.HttpMethod.PATCH -> PatchMapping::class
            PathItem.HttpMethod.DELETE -> DeleteMapping::class
            else -> null
        }
        return if (annotationClass != null) {
            val builder = AnnotationSpec.builder(annotationClass).addMember("value = [%S]", path)
            getSuccessfulReply(operationEntry.value)
                .flatMap(::getMediaType)
                .map { it.key }
                .ifPresent { builder.addMember("produces = [%S]", it) }

            Optional.ofNullable(operationEntry.value.requestBody)
                .map { it.content }
                .flatMap(::getMediaType)
                .map { it.key }
                .filter { it.isNotBlank() && it != "application/json" }
                .ifPresent { builder.addMember("consumes = [%S]", it) }
            builder.build()
        } else null
    }

    private fun determineReturnKotlinType(operation: Operation, openAPI: OpenAPI, parent: TypeSpec.Builder): TypeName =
        getSuccessfulReply(operation)
            .stream().asSequence()
            .flatMap { c: Content ->
                getContentType(c, openAPI, parent)
            }
            .map { it.typeName }
            .firstOrNull() ?: UNIT

    private data class RequestPartParams(
        val typeName: TypeName,
        val name: String,
        val annotation: AnnotationSpec
    )

    private fun getContentType(
        content: Content,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder
    ): Sequence<RequestPartParams> {
        val mediaTypeEntry = Optional.ofNullable(content)
            .flatMap { getMediaType(it) }
        if (mediaTypeEntry.isEmpty) {
            return sequenceOf()
        } else {
            val entry = mediaTypeEntry.get()
            if ("multipart/form-data".equals(entry.key, ignoreCase = true)) {
                //Multipart
                return entry.value.schema?.properties?.asSequence().orEmpty()
                    .map { (name, schema) ->
                        RequestPartParams(
                            name = name,
                            typeName = typeDefiner.defineKotlinType(schema, openAPI, parent, null, null),
                            annotation = AnnotationSpec.builder(RequestPart::class)
                                .addMember("name = %S", name).build()
                        )
                    }

            } else {
                //Single-part
                return Optional.ofNullable(entry.value.schema).stream().asSequence()
                    .map { typeDefiner.defineKotlinType(it, openAPI, parent, null, null) }
                    .map {
                        RequestPartParams(
                            name = "request",
                            typeName = it,
                            annotation = AnnotationSpec
                                .builder(org.springframework.web.bind.annotation.RequestBody::class)
                                .build()
                        )
                    }
            }
        }
    }
}