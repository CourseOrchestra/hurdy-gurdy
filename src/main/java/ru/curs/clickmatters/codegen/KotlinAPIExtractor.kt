package ru.curs.clickmatters.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import java.util.*
import javax.servlet.http.HttpServletResponse
import kotlin.reflect.KClass

class KotlinAPIExtractor(typeDefiner: TypeDefiner<TypeSpec>, generateResponseParameter: Boolean) :
    APIExtractor<TypeSpec, TypeSpec.Builder>(typeDefiner, generateResponseParameter) {

    internal override fun builder(): BuilderHolder {
        return object : BuilderHolder(TypeSpec.interfaceBuilder("Controller")) {
            override fun build(): TypeSpec = builder.build()
        }
    }

    public override fun buildMethod(
        classBuilder: TypeSpec.Builder,
        stringPathItemEntry: Map.Entry<String, PathItem>,
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>
    ) {
        val methodBuilder = FunSpec
            .builder(CaseUtils.snakeToCamel(operationEntry.value.operationId))
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.key)?.let(methodBuilder::addAnnotation)
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnKotlinType(operationEntry.value, classBuilder))
        Optional.ofNullable(operationEntry.value.requestBody).map { obj: RequestBody -> obj.content }
            .map { getContentType(it, classBuilder) }
            .ifPresent { typeName: TypeName ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        "request",
                        typeName
                    ).addAnnotation(org.springframework.web.bind.annotation.RequestBody::class).build()
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
                        typeDefiner.defineKotlinType(parameter.schema, classBuilder),
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
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.snakeToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, classBuilder),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(
                                RequestParam::class
                            ).addMember("required = %L", parameter.required)
                                .addMember("name = %S", parameter.name).build()
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
                        typeDefiner.defineKotlinType(parameter.schema, classBuilder),
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
            PathItem.HttpMethod.DELETE -> DeleteMapping::class
            else -> null
        }
        return if (annotationClass != null) {
            val builder = AnnotationSpec.builder(annotationClass).addMember("value = %S", path)
            getSuccessfulReply(operationEntry.value)
                .flatMap(::getMediaType)
                .map { it.key }
                .ifPresent { builder.addMember("produces = %S", it) }
            builder.build()
        } else null
    }

    private fun determineReturnKotlinType(operation: Operation, parent: TypeSpec.Builder): TypeName =
        getSuccessfulReply(operation).map { c: Content ->
            getContentType(
                c,
                parent
            )
        }.orElse(UNIT)

    private fun getContentType(content: Content, parent: TypeSpec.Builder): TypeName =
        Optional.ofNullable(content)
            .flatMap(::getMediaType)
            .map { it.value }
            .map { it.schema }
            .map { typeDefiner.defineKotlinType(it, parent) }
            .orElse(UNIT)
}