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

package ru.curs.hurdygurdy

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
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
        { name, role ->
            val b = TypeSpec.interfaceBuilder(normalizeToCamel(name))
            if (params.framework == Framework.QUARKUS) {
                b.addAnnotation(
                    AnnotationSpec.builder(ClassName("jakarta.ws.rs", "Path"))
                        .addMember("%S", "").build()
                )
                if (role == Role.CLIENT) {
                    b.addAnnotation(AnnotationSpec.builder(MP_REGISTER_REST_CLIENT).build())
                }
            }
            b
        },
        TypeSpec.Builder::build
    ) {

    private companion object {
        val JAXRS_PATH = ClassName("jakarta.ws.rs", "Path")
        val JAXRS_GET = ClassName("jakarta.ws.rs", "GET")
        val JAXRS_POST = ClassName("jakarta.ws.rs", "POST")
        val JAXRS_PUT = ClassName("jakarta.ws.rs", "PUT")
        val JAXRS_PATCH = ClassName("jakarta.ws.rs", "PATCH")
        val JAXRS_DELETE = ClassName("jakarta.ws.rs", "DELETE")
        val JAXRS_PRODUCES = ClassName("jakarta.ws.rs", "Produces")
        val JAXRS_CONSUMES = ClassName("jakarta.ws.rs", "Consumes")
        val JAXRS_PATH_PARAM = ClassName("jakarta.ws.rs", "PathParam")
        val JAXRS_QUERY_PARAM = ClassName("jakarta.ws.rs", "QueryParam")
        val JAXRS_DEFAULT_VALUE = ClassName("jakarta.ws.rs", "DefaultValue")
        val JAXRS_HEADER_PARAM = ClassName("jakarta.ws.rs", "HeaderParam")
        val JAXRS_CONTEXT = ClassName("jakarta.ws.rs.core", "Context")
        val JAXRS_RESPONSE = ClassName("jakarta.ws.rs.core", "Response")
        val JAXRS_REQUEST_CONTEXT = ClassName("jakarta.ws.rs.container", "ContainerRequestContext")
        val QUARKUS_REST_FORM = ClassName("org.jboss.resteasy.reactive", "RestForm")
        val MP_REGISTER_REST_CLIENT =
            ClassName("org.eclipse.microprofile.rest.client.inject", "RegisterRestClient")
        val SPRING_GET_EXCHANGE = ClassName("org.springframework.web.service.annotation", "GetExchange")
        val SPRING_POST_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PostExchange")
        val SPRING_PUT_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PutExchange")
        val SPRING_PATCH_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PatchExchange")
        val SPRING_DELETE_EXCHANGE = ClassName("org.springframework.web.service.annotation", "DeleteExchange")
        val SPRING_RESPONSE_ENTITY = ClassName("org.springframework.http", "ResponseEntity")

        // Position-dependent target types for `format: binary`: a multipart part is
        // a MultipartFile/FileUpload, a raw body or response is a converter-backed
        // body type (Spring Resource / JAX-RS InputStream). A bare binary DTO
        // property is ByteArray and handled by the type definer.
        val MULTIPART_FILE = ClassName("org.springframework.web.multipart", "MultipartFile")
        val QUARKUS_FILE_UPLOAD = ClassName("org.jboss.resteasy.reactive.multipart", "FileUpload")
        val SPRING_RESOURCE = ClassName("org.springframework.core.io", "Resource")
        val INPUT_STREAM = ClassName("java.io", "InputStream")
    }

    public override fun buildMethod(
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        stringPathItemEntry: Map.Entry<String, PathItem>,
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        operationId: String,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        if (framework == Framework.QUARKUS) {
            buildQuarkusMethod(
                openAPI, classBuilder, stringPathItemEntry,
                operationEntry, operationId, role, generateResponseParameter
            )
        } else if (role == Role.CLIENT) {
            buildSpringClientMethod(
                openAPI, classBuilder, stringPathItemEntry,
                operationEntry, operationId, generateResponseParameter
            )
        } else {
            buildSpringMethod(
                openAPI, classBuilder, stringPathItemEntry,
                operationEntry, operationId, generateResponseParameter
            )
        }
    }

    private fun buildSpringMethod(
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
        requestBodyParams(operationEntry.value, openAPI, classBuilder, false)
            .forEach { paramSpec: RequestPartParams ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(paramSpec.name),
                        paramSpec.typeName
                    ).addAnnotation(paramSpec.annotation!!).build()
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
                        CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
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
                    .addMember("required = %L", parameter.required == true)
                    .addMember("name = %S", parameter.name)
                typeDefiner.effectiveDefault(parameter.schema, openAPI)
                    ?.let { builder.addMember("defaultValue = %S", it) }
                val annotationSpec = builder.build()
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    )
                        .addAnnotation(
                            annotationSpec
                        ).build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "header".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                val builder = AnnotationSpec.builder(RequestHeader::class)
                    .addMember("required = %L", parameter.required == true)
                    .addMember("name = %S", parameter.name)
                typeDefiner.effectiveDefault(parameter.schema, openAPI)
                    ?.let { builder.addMember("defaultValue = %S", it) }
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    ).addAnnotation(builder.build()).build()
                )
            }
        if (generateResponseParameter) {
            if (isIncludeRequest(operationEntry.value)) {
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

    private fun buildQuarkusMethod(
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        stringPathItemEntry: Map.Entry<String, PathItem>,
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        operationId: String,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        val methodBuilder = FunSpec
            .builder(operationId)
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        getQuarkusMethodAnnotations(operationEntry, stringPathItemEntry.key)
            .forEach(methodBuilder::addAnnotation)

        val dtoReturn = determineReturnKotlinType(operationEntry.value, openAPI, classBuilder)
        if (generateResponseParameter) {
            methodBuilder.returns(JAXRS_RESPONSE)
            methodBuilder.addKdoc(
                "@return a Response whose entity is expected to be %L\n",
                if (dtoReturn == UNIT) "empty (no body)" else dtoReturn.toString()
            )
        } else {
            methodBuilder.returns(dtoReturn)
        }

        requestBodyParams(operationEntry.value, openAPI, classBuilder, true)
            .forEach { paramSpec: RequestPartParams ->
                val pb = ParameterSpec.builder(CaseUtils.toIdentifier(paramSpec.name), paramSpec.typeName)
                paramSpec.annotation?.let(pb::addAnnotation)
                methodBuilder.addParameter(pb.build())
            }

        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter -> "path".equals(parameter.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(JAXRS_PATH_PARAM)
                                .addMember("%S", parameter.name).build()
                        )
                        .build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter -> "query".equals(parameter.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                val pb = ParameterSpec.builder(
                    CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                    parameterType(parameter, openAPI, classBuilder),
                )
                    .addAnnotation(
                        AnnotationSpec.builder(JAXRS_QUERY_PARAM)
                            .addMember("%S", parameter.name).build()
                    )
                typeDefiner.effectiveDefault(parameter.schema, openAPI)?.let {
                    pb.addAnnotation(
                        AnnotationSpec.builder(JAXRS_DEFAULT_VALUE)
                            .addMember("%S", it).build()
                    )
                }
                methodBuilder.addParameter(pb.build())
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter -> "header".equals(parameter.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                val pb = ParameterSpec.builder(
                    CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.name)),
                    parameterType(parameter, openAPI, classBuilder),
                ).addAnnotation(
                    AnnotationSpec.builder(JAXRS_HEADER_PARAM)
                        .addMember("%S", parameter.name).build()
                )
                typeDefiner.effectiveDefault(parameter.schema, openAPI)?.let {
                    pb.addAnnotation(
                        AnnotationSpec.builder(JAXRS_DEFAULT_VALUE)
                            .addMember("%S", it).build()
                    )
                }
                methodBuilder.addParameter(pb.build())
            }
        if (generateResponseParameter && isIncludeRequest(operationEntry.value) && role == Role.CONTROLLER) {
            methodBuilder.addParameter(
                ParameterSpec.builder("requestContext", JAXRS_REQUEST_CONTEXT)
                    .addAnnotation(JAXRS_CONTEXT)
                    .build()
            )
        }
        classBuilder.addFunction(methodBuilder.build())
    }

    private fun buildSpringClientMethod(
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
        getSpringExchangeAnnotationSpec(operationEntry, stringPathItemEntry.key)
            ?.let(methodBuilder::addAnnotation)
        val dtoReturn = determineReturnKotlinType(operationEntry.value, openAPI, classBuilder)
        if (generateResponseParameter) {
            methodBuilder.returns(SPRING_RESPONSE_ENTITY.parameterizedBy(dtoReturn.copy(nullable = false)))
        } else {
            methodBuilder.returns(dtoReturn)
        }
        requestBodyParams(operationEntry.value, openAPI, classBuilder, false)
            .forEach { paramSpec: RequestPartParams ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(CaseUtils.toIdentifier(paramSpec.name), paramSpec.typeName)
                        .addAnnotation(paramSpec.annotation!!).build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { "path".equals(it.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    ).addAnnotation(
                        AnnotationSpec.builder(PathVariable::class)
                            .addMember("name = %S", parameter.name).build()
                    ).build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { "query".equals(it.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                val builder = AnnotationSpec.builder(RequestParam::class)
                    .addMember("required = %L", parameter.required == true)
                    .addMember("name = %S", parameter.name)
                typeDefiner.effectiveDefault(parameter.schema, openAPI)
                    ?.let { builder.addMember("defaultValue = %S", it) }
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    ).addAnnotation(builder.build()).build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { "header".equals(it.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                val builder = AnnotationSpec.builder(RequestHeader::class)
                    .addMember("required = %L", parameter.required == true)
                    .addMember("name = %S", parameter.name)
                typeDefiner.effectiveDefault(parameter.schema, openAPI)
                    ?.let { builder.addMember("defaultValue = %S", it) }
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.name)),
                        parameterType(parameter, openAPI, classBuilder),
                    ).addAnnotation(builder.build()).build()
                )
            }
        classBuilder.addFunction(methodBuilder.build())
    }

    private fun getSpringExchangeAnnotationSpec(
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        path: String
    ): AnnotationSpec? {
        val annotationClass: ClassName = when (operationEntry.key) {
            PathItem.HttpMethod.GET -> SPRING_GET_EXCHANGE
            PathItem.HttpMethod.POST -> SPRING_POST_EXCHANGE
            PathItem.HttpMethod.PUT -> SPRING_PUT_EXCHANGE
            PathItem.HttpMethod.PATCH -> SPRING_PATCH_EXCHANGE
            PathItem.HttpMethod.DELETE -> SPRING_DELETE_EXCHANGE
            else -> return null
        }
        val builder = AnnotationSpec.builder(annotationClass).addMember("value = %S", path)
        getSuccessfulReply(operationEntry.value)
            .flatMap(::getMediaType)
            .map { it.key }
            .ifPresent { builder.addMember("accept = [%S]", it) }
        Optional.ofNullable(operationEntry.value.requestBody)
            .map { it.content }
            .flatMap(::getMediaType)
            .map { it.key }
            .filter { it.isNotBlank() && it != "application/json" }
            .ifPresent { builder.addMember("contentType = %S", it) }
        return builder.build()
    }

    private fun isIncludeRequest(operation: Operation): Boolean =
        Optional.ofNullable(operation.extensions)
            .map { it["x-include-request"] }
            .map {
                when (it) {
                    is Boolean -> it
                    is String -> it.toBoolean()
                    else -> false
                }
            }.orElse(false)!!

    private fun getQuarkusMethodAnnotations(
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        path: String
    ): List<AnnotationSpec> {
        val verb: ClassName = when (operationEntry.key) {
            PathItem.HttpMethod.GET -> JAXRS_GET
            PathItem.HttpMethod.POST -> JAXRS_POST
            PathItem.HttpMethod.PUT -> JAXRS_PUT
            PathItem.HttpMethod.PATCH -> JAXRS_PATCH
            PathItem.HttpMethod.DELETE -> JAXRS_DELETE
            else -> return emptyList()
        }
        val result = mutableListOf(
            AnnotationSpec.builder(verb).build(),
            AnnotationSpec.builder(JAXRS_PATH).addMember("%S", path).build()
        )
        getSuccessfulReply(operationEntry.value)
            .flatMap(::getMediaType)
            .map { it.key }
            .ifPresent {
                result.add(AnnotationSpec.builder(JAXRS_PRODUCES).addMember("%S", it).build())
            }
        Optional.ofNullable(operationEntry.value.requestBody)
            .map { it.content }
            .flatMap(::getMediaType)
            .map { it.key }
            .filter { it.isNotBlank() }
            .ifPresent {
                result.add(AnnotationSpec.builder(JAXRS_CONSUMES).addMember("%S", it).build())
            }
        return result
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

    /**
     * The Kotlin return type: the body of the successful reply, or `Unit` when
     * that reply has none.
     *
     * The body of a documented reply is there — a response `content` is what the
     * endpoint says it sends back — so the return type is non-null (`required =
     * true` below) unless the schema itself admits null. See
     * [isNullableParameter] for the same distinction on the way in.
     */
    private fun determineReturnKotlinType(operation: Operation, openAPI: OpenAPI, parent: TypeSpec.Builder): TypeName =
        getSuccessfulReply(operation)
            .stream().asSequence()
            .flatMap { c: Content ->
                getContentType(c, openAPI, parent, false, true)
            }
            .map { it.typeName }
            .firstOrNull() ?: UNIT

    private data class RequestPartParams(
        val typeName: TypeName,
        val name: String,
        val annotation: AnnotationSpec?
    )

    /**
     * The request-body parameters of an operation, if it has a body at all.
     *
     * The body is non-null only when the operation declares `required: true`:
     * OpenAPI defaults `requestBody.required` to false, and a body that may be
     * omitted from the call is exactly a nullable argument.
     */
    private fun requestBodyParams(
        operation: Operation,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        quarkus: Boolean
    ): Sequence<RequestPartParams> {
        val body = operation.requestBody ?: return sequenceOf()
        val content = body.content ?: return sequenceOf()
        return getContentType(content, openAPI, parent, quarkus, body.required == true)
    }

    /**
     * @param required the value is always present — a `required` request body,
     *                 or a response body. When false the resulting type is
     *                 nullable whatever the schema says.
     */
    private fun getContentType(
        content: Content,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        quarkus: Boolean,
        required: Boolean
    ): Sequence<RequestPartParams> {
        val mediaTypeEntry = Optional.ofNullable(content)
            .flatMap { getMediaType(it) }
        if (mediaTypeEntry.isEmpty) {
            return sequenceOf()
        } else {
            val entry = mediaTypeEntry.get()
            return if ("multipart/form-data".equals(entry.key, ignoreCase = true)) {
                //Multipart
                // A part is present only if the body itself is required AND the
                // multipart object lists that part in its own `required`.
                val requiredParts = entry.value.schema?.required?.toSet().orEmpty()
                entry.value.schema?.properties?.asSequence().orEmpty()
                    .map { (name, schema) ->
                        val present = required && name in requiredParts
                        val nullable = !present || typeDefiner.isNullableType(schema, openAPI)
                        RequestPartParams(
                            name = name,
                            // A binary part, or an array of them, is an uploaded
                            // file (MultipartFile / FileUpload); other parts
                            // resolve normally.
                            typeName = uploadType(schema, openAPI)?.copy(nullable = nullable)
                                ?: typeDefiner.defineKotlinType(schema, openAPI, parent, null, nullable),
                            annotation = if (quarkus)
                                AnnotationSpec.builder(QUARKUS_REST_FORM)
                                    .addMember("%S", name).build()
                            else
                                AnnotationSpec.builder(RequestPart::class)
                                    .addMember("name = %S", name)
                                    .optional(present).build()
                        )
                    }

            } else {
                //Single-part
                Optional.ofNullable(entry.value.schema).stream().asSequence()
                    // A binary single-part body/response is a converter-backed body
                    // type (Resource / InputStream), not a multipart part.
                    .map {
                        val nullable = !required || typeDefiner.isNullableType(it, openAPI)
                        if (isBinary(it)) binaryBodyType().copy(nullable = nullable)
                        else typeDefiner.defineKotlinType(it, openAPI, parent, null, nullable)
                    }
                    .map {
                        RequestPartParams(
                            name = "request",
                            typeName = it,
                            annotation = if (quarkus)
                                null
                            else
                                AnnotationSpec
                                    .builder(org.springframework.web.bind.annotation.RequestBody::class)
                                    .optional(required).build()
                        )
                    }
            }
        }
    }

    /**
     * Spells out `required = false` on a Spring `@RequestBody` / `@RequestPart`
     * when the value may be absent.
     *
     * Both annotations default to `required = true` and make Spring reject a
     * request that omits the body or the part, so the nullable type the caller
     * derived for an optional body could never actually be null. `required =
     * true` is left implicit, which is also what every pre-existing signature
     * says.
     */
    private fun AnnotationSpec.Builder.optional(present: Boolean): AnnotationSpec.Builder =
        if (present) this else addMember("required = %L", false)

    /** Whether a schema is `type: string, format: binary` (OpenAPI 3.0 or 3.1). */
    private fun isBinary(schema: Schema<*>?): Boolean {
        if (schema == null) {
            return false
        }
        return "string" == TypeDefiner.effectiveType(schema) && "binary" == schema.format
    }

    /**
     * The upload type of a binary multipart part, or null when the part is not
     * binary at all: `MultipartFile` / `FileUpload` for a scalar
     * `format: binary`, a `List` of it for an array of them (a part sent several
     * times over).
     *
     * An array had to be spelled out here: a bare binary schema means
     * `ByteArray` to the type definer, which is right for a base64 property of a
     * JSON DTO but never for a multipart part.
     */
    private fun uploadType(schema: Schema<*>?, openAPI: OpenAPI): TypeName? {
        if (isBinary(schema)) {
            return multipartPartType()
        }
        if (schema != null && TypeDefiner.isArraySchema(schema)) {
            val items = schema.items
            val itemType = uploadType(items, openAPI) ?: return null
            return LIST.parameterizedBy(
                itemType.copy(nullable = typeDefiner.isNullableType(items, openAPI))
            )
        }
        return null
    }

    // Keyed on the actual framework (not the annotation-context flag, which a
    // return type derives with `quarkus = false`). Returned non-null; the caller
    // applies the nullability it worked out for the surrounding body or part.
    private fun multipartPartType(): TypeName =
        if (framework == Framework.QUARKUS) QUARKUS_FILE_UPLOAD else MULTIPART_FILE

    private fun binaryBodyType(): TypeName =
        if (framework == Framework.QUARKUS) INPUT_STREAM else SPRING_RESOURCE

    /**
     * Whether the Kotlin type of `parameter` must admit null.
     *
     * A value is nullable when it may be ABSENT, or when its schema says it may
     * be null — two independent questions, both asked here. A path variable is
     * part of the URL and so is always present; a query or header parameter is
     * present when it is `required`, and also when a `default` applies, because
     * the generator emits that default into the annotation
     * (`@RequestParam(defaultValue = ...)`, `@RequestHeader(defaultValue = ...)`,
     * `@DefaultValue`) and the framework substitutes it.
     *
     * The default is read with [TypeDefiner.effectiveDefault], the same call the
     * annotations are built from, so "assumed present" and "default emitted"
     * cannot drift apart: claiming presence without emitting the default would
     * hand the caller a non-null parameter that the framework fills with null.
     */
    private fun isNullableParameter(parameter: Parameter, openAPI: OpenAPI): Boolean {
        val present = "path".equals(parameter.getIn(), ignoreCase = true)
                || parameter.required == true
                || typeDefiner.effectiveDefault(parameter.schema, openAPI) != null
        return !present || typeDefiner.isNullableType(parameter.schema, openAPI)
    }

    private fun parameterType(parameter: Parameter, openAPI: OpenAPI, parent: TypeSpec.Builder): TypeName =
        typeDefiner.defineKotlinType(
            parameter.schema, openAPI, parent, null, isNullableParameter(parameter, openAPI)
        )
}
