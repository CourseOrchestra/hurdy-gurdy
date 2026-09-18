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
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import io.swagger.v3.oas.models.PathItem
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import kotlin.reflect.KClass

/**
 * The annotation vocabulary of one target web framework, for Kotlin output.
 *
 * Counterpart of [JavaFrameworkBinding]; see its documentation for why the
 * algorithm and the vocabulary are separated. There has to be one of these per
 * language until the model of step 3 exists, because every method here returns a
 * KotlinPoet object. The two are expected to answer alike — `ApiParityTest`
 * checks that they do.
 */
internal interface KotlinFrameworkBinding {

    /**
     * The annotations mapping the function to an HTTP endpoint, in order. An
     * empty list means the framework has no mapping for this verb.
     */
    fun methodAnnotations(operation: OperationModel): List<AnnotationSpec>

    /** The annotations binding a path parameter, which never carries a default. */
    fun pathParamAnnotations(parameter: ParameterModel): List<AnnotationSpec>

    /** The annotations binding a query parameter. */
    fun queryParamAnnotations(parameter: ParameterModel): List<AnnotationSpec>

    /** The annotations binding a header parameter. */
    fun headerParamAnnotations(parameter: ParameterModel): List<AnnotationSpec>

    /**
     * The annotation marking the single-part request body, or null when the
     * framework infers it from the signature.
     *
     * @param present whether the body is always there, which Spring states as
     *                `required` and Kotlin reflects in the parameter's nullability
     */
    fun bodyAnnotation(present: Boolean): AnnotationSpec?

    /** The annotation binding one part of a multipart request body. */
    fun multipartPartAnnotation(part: PartModel): AnnotationSpec

    /** The type of a binary multipart part: an uploaded file. */
    fun multipartPartType(): TypeName

    /** The type of a binary single-part body: a converter-backed stream. */
    fun binaryBodyType(): TypeName

    /** Sets the return type, wrapped as the framework requires. */
    fun applyReturn(method: FunSpec.Builder, dtoReturn: TypeName, generateResponseParameter: Boolean)

    /** Adds the framework's server-side context parameters. */
    fun addContextParameters(
        method: FunSpec.Builder,
        includeRequest: Boolean,
        role: Role,
        generateResponseParameter: Boolean
    )
}

/**
 * Spring Web MVC, server side. Shared by [Role.CONTROLLER] and [Role.API].
 */
internal open class KotlinSpringBinding : KotlinFrameworkBinding {

    override fun methodAnnotations(operation: OperationModel): List<AnnotationSpec> {
        val annotationClass: KClass<out Annotation> = when (operation.httpMethod()) {
            PathItem.HttpMethod.GET -> GetMapping::class
            PathItem.HttpMethod.POST -> PostMapping::class
            PathItem.HttpMethod.PUT -> PutMapping::class
            PathItem.HttpMethod.PATCH -> PatchMapping::class
            PathItem.HttpMethod.DELETE -> DeleteMapping::class
            else -> return listOf()
        }
        val builder = AnnotationSpec.builder(annotationClass)
            .addMember("value = [%S]", operation.path())
        operation.response()?.mediaType()
            ?.let { builder.addMember("produces = [%S]", it) }
        operation.body()?.mediaType()
            // application/json is Spring's own default, so saying it adds nothing.
            ?.takeIf { it.isNotBlank() && it != "application/json" }
            ?.let { builder.addMember("consumes = [%S]", it) }
        return listOf(builder.build())
    }

    override fun pathParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        listOf(
            AnnotationSpec.builder(PathVariable::class)
                .addMember("name = %S", parameter.specName()).build()
        )

    override fun queryParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        listOf(springParam(RequestParam::class, parameter))

    override fun headerParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        listOf(springParam(RequestHeader::class, parameter))

    /**
     * Spring states everything about a bound parameter in one annotation: whether
     * it is required, its name on the wire, and its default when it has one.
     */
    private fun springParam(
        annotationClass: KClass<out Annotation>,
        parameter: ParameterModel
    ): AnnotationSpec {
        val builder = AnnotationSpec.builder(annotationClass)
            .addMember("required = %L", parameter.required())
            .addMember("name = %S", parameter.specName())
        parameter.defaultValue()?.let { builder.addMember("defaultValue = %S", it) }
        return builder.build()
    }

    override fun bodyAnnotation(present: Boolean): AnnotationSpec =
        AnnotationSpec.builder(org.springframework.web.bind.annotation.RequestBody::class)
            .optional(present).build()

    override fun multipartPartAnnotation(part: PartModel): AnnotationSpec =
        AnnotationSpec.builder(RequestPart::class)
            .addMember("name = %S", part.name())
            .optional(part.present()).build()

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

    override fun multipartPartType(): TypeName = MULTIPART_FILE

    override fun binaryBodyType(): TypeName = SPRING_RESOURCE

    override fun applyReturn(method: FunSpec.Builder, dtoReturn: TypeName, generateResponseParameter: Boolean) {
        // A Spring controller returns the DTO itself; the response is reached
        // through the HttpServletResponse parameter instead.
        method.returns(dtoReturn)
    }

    override fun addContextParameters(
        method: FunSpec.Builder,
        includeRequest: Boolean,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        if (!generateResponseParameter) {
            return
        }
        if (includeRequest) {
            method.addParameter(ParameterSpec.builder("request", HttpServletRequest::class).build())
        }
        method.addParameter(ParameterSpec.builder("response", HttpServletResponse::class).build())
    }

    companion object {
        val MULTIPART_FILE = ClassName("org.springframework.web.multipart", "MultipartFile")
        val SPRING_RESOURCE = ClassName("org.springframework.core.io", "Resource")
    }
}

/**
 * Spring's declarative HTTP interface: the `@GetExchange` family. A binding
 * rather than a role special case — it shares Spring's parameter annotations but
 * maps the method differently and returns a `ResponseEntity`.
 */
internal class KotlinSpringClientBinding : KotlinSpringBinding() {

    override fun methodAnnotations(operation: OperationModel): List<AnnotationSpec> {
        val annotationClass = when (operation.httpMethod()) {
            PathItem.HttpMethod.GET -> SPRING_GET_EXCHANGE
            PathItem.HttpMethod.POST -> SPRING_POST_EXCHANGE
            PathItem.HttpMethod.PUT -> SPRING_PUT_EXCHANGE
            PathItem.HttpMethod.PATCH -> SPRING_PATCH_EXCHANGE
            PathItem.HttpMethod.DELETE -> SPRING_DELETE_EXCHANGE
            else -> return listOf()
        }
        val builder = AnnotationSpec.builder(annotationClass)
            .addMember("value = %S", operation.path())
        // The client states what it will accept, where the server states what it
        // produces: the same media type read from the other end.
        operation.response()?.mediaType()
            ?.let { builder.addMember("accept = [%S]", it) }
        operation.body()?.mediaType()
            ?.takeIf { it.isNotBlank() && it != "application/json" }
            ?.let { builder.addMember("contentType = %S", it) }
        return listOf(builder.build())
    }

    override fun applyReturn(method: FunSpec.Builder, dtoReturn: TypeName, generateResponseParameter: Boolean) {
        if (generateResponseParameter) {
            // A client has no servlet response to be handed, so the status and
            // headers it was asked for come back inside a ResponseEntity.
            method.returns(SPRING_RESPONSE_ENTITY.parameterizedBy(dtoReturn.copy(nullable = false)))
        } else {
            method.returns(dtoReturn)
        }
    }

    override fun addContextParameters(
        method: FunSpec.Builder,
        includeRequest: Boolean,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        // A client interface takes no server-side context handles.
    }

    private companion object {
        val SPRING_GET_EXCHANGE = ClassName("org.springframework.web.service.annotation", "GetExchange")
        val SPRING_POST_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PostExchange")
        val SPRING_PUT_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PutExchange")
        val SPRING_PATCH_EXCHANGE = ClassName("org.springframework.web.service.annotation", "PatchExchange")
        val SPRING_DELETE_EXCHANGE = ClassName("org.springframework.web.service.annotation", "DeleteExchange")
        val SPRING_RESPONSE_ENTITY = ClassName("org.springframework.http", "ResponseEntity")
    }
}

/**
 * Quarkus, through JAX-RS. One binding for every role: a client differs only by
 * the interface-level `@RegisterRestClient`, which the extractor adds, and by
 * having no `@Context` parameter.
 */
internal class KotlinQuarkusBinding : KotlinFrameworkBinding {

    override fun methodAnnotations(operation: OperationModel): List<AnnotationSpec> {
        val verb = when (operation.httpMethod()) {
            PathItem.HttpMethod.GET -> JAXRS_GET
            PathItem.HttpMethod.POST -> JAXRS_POST
            PathItem.HttpMethod.PUT -> JAXRS_PUT
            PathItem.HttpMethod.PATCH -> JAXRS_PATCH
            PathItem.HttpMethod.DELETE -> JAXRS_DELETE
            else -> return listOf()
        }
        val result = mutableListOf(
            AnnotationSpec.builder(verb).build(),
            AnnotationSpec.builder(JAXRS_PATH).addMember("%S", operation.path()).build()
        )
        operation.response()?.mediaType()
            ?.let { result.add(AnnotationSpec.builder(JAXRS_PRODUCES).addMember("%S", it).build()) }
        // Unlike Spring's `consumes`, application/json is NOT filtered out: JAX-RS
        // has no such default, so leaving it out would widen what the resource accepts.
        operation.body()?.mediaType()
            ?.takeIf { it.isNotBlank() }
            ?.let { result.add(AnnotationSpec.builder(JAXRS_CONSUMES).addMember("%S", it).build()) }
        return result
    }

    override fun pathParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        listOf(jaxrsParam(JAXRS_PATH_PARAM, parameter))

    override fun queryParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        withDefault(jaxrsParam(JAXRS_QUERY_PARAM, parameter), parameter.defaultValue())

    override fun headerParamAnnotations(parameter: ParameterModel): List<AnnotationSpec> =
        withDefault(jaxrsParam(JAXRS_HEADER_PARAM, parameter), parameter.defaultValue())

    private fun jaxrsParam(annotationClass: ClassName, parameter: ParameterModel): AnnotationSpec =
        AnnotationSpec.builder(annotationClass).addMember("%S", parameter.specName()).build()

    /**
     * JAX-RS has no `defaultValue` member; the default is a separate annotation on
     * the same parameter, and there is nowhere to state `required` at all.
     */
    private fun withDefault(param: AnnotationSpec, defaultValue: String?): List<AnnotationSpec> =
        if (defaultValue == null) {
            listOf(param)
        } else {
            listOf(param, AnnotationSpec.builder(JAXRS_DEFAULT_VALUE).addMember("%S", defaultValue).build())
        }

    // JAX-RS infers the entity from the signature: the one unannotated parameter.
    override fun bodyAnnotation(present: Boolean): AnnotationSpec? = null

    override fun multipartPartAnnotation(part: PartModel): AnnotationSpec =
        AnnotationSpec.builder(QUARKUS_REST_FORM).addMember("%S", part.name()).build()

    override fun multipartPartType(): TypeName = QUARKUS_FILE_UPLOAD

    override fun binaryBodyType(): TypeName = INPUT_STREAM

    override fun applyReturn(method: FunSpec.Builder, dtoReturn: TypeName, generateResponseParameter: Boolean) {
        if (generateResponseParameter) {
            // JAX-RS carries status and headers in the return value, so the DTO the
            // caller should put in it is recorded in the kdoc instead.
            method.returns(JAXRS_RESPONSE)
            method.addKdoc(
                "@return a Response whose entity is expected to be %L\n",
                if (dtoReturn == UNIT) "empty (no body)" else dtoReturn.toString()
            )
        } else {
            method.returns(dtoReturn)
        }
    }

    override fun addContextParameters(
        method: FunSpec.Builder,
        includeRequest: Boolean,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        if (generateResponseParameter && includeRequest && role == Role.CONTROLLER) {
            method.addParameter(
                ParameterSpec.builder("requestContext", JAXRS_REQUEST_CONTEXT)
                    .addAnnotation(JAXRS_CONTEXT)
                    .build()
            )
        }
    }

    companion object {
        val JAXRS_PATH = ClassName("jakarta.ws.rs", "Path")
        private val JAXRS_GET = ClassName("jakarta.ws.rs", "GET")
        private val JAXRS_POST = ClassName("jakarta.ws.rs", "POST")
        private val JAXRS_PUT = ClassName("jakarta.ws.rs", "PUT")
        private val JAXRS_PATCH = ClassName("jakarta.ws.rs", "PATCH")
        private val JAXRS_DELETE = ClassName("jakarta.ws.rs", "DELETE")
        private val JAXRS_PRODUCES = ClassName("jakarta.ws.rs", "Produces")
        private val JAXRS_CONSUMES = ClassName("jakarta.ws.rs", "Consumes")
        private val JAXRS_PATH_PARAM = ClassName("jakarta.ws.rs", "PathParam")
        private val JAXRS_QUERY_PARAM = ClassName("jakarta.ws.rs", "QueryParam")
        private val JAXRS_DEFAULT_VALUE = ClassName("jakarta.ws.rs", "DefaultValue")
        private val JAXRS_HEADER_PARAM = ClassName("jakarta.ws.rs", "HeaderParam")
        private val JAXRS_CONTEXT = ClassName("jakarta.ws.rs.core", "Context")
        private val JAXRS_RESPONSE = ClassName("jakarta.ws.rs.core", "Response")
        private val JAXRS_REQUEST_CONTEXT =
            ClassName("jakarta.ws.rs.container", "ContainerRequestContext")
        private val QUARKUS_REST_FORM = ClassName("org.jboss.resteasy.reactive", "RestForm")
        private val QUARKUS_FILE_UPLOAD =
            ClassName("org.jboss.resteasy.reactive.multipart", "FileUpload")
        private val INPUT_STREAM = ClassName("java.io", "InputStream")
    }
}
