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
import ru.curs.hurdygurdy.CaseUtils.normalizeToCamel
import java.util.Locale
import java.util.Optional
import kotlin.streams.asSequence

/**
 * Generates one Kotlin interface per OpenAPI tag, with one function per
 * operation.
 *
 * There is a single method-building algorithm here; everything that changes
 * between Spring, the Spring HTTP interface and Quarkus is supplied by a
 * [KotlinFrameworkBinding]. It used to be three copies of that algorithm, one
 * per dialect, mirroring three more in Java.
 */
class KotlinAPIExtractor(
    private val typeDefiner: KotlinTypeDefiner,
    params: GeneratorParams
) :
    APIExtractor<TypeSpec, TypeSpec.Builder>(
        params,
        { name, role ->
            val b = TypeSpec.interfaceBuilder(normalizeToCamel(name))
            if (params.framework == Framework.QUARKUS) {
                b.addAnnotation(
                    AnnotationSpec.builder(KotlinQuarkusBinding.JAXRS_PATH)
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

    /**
     * The annotation dialect to generate this role in. Quarkus speaks one for
     * every role; Spring speaks two, and which of them applies is decided here
     * rather than inside the algorithm.
     */
    private fun binding(role: Role): KotlinFrameworkBinding =
        when {
            framework == Framework.QUARKUS -> KotlinQuarkusBinding()
            role == Role.CLIENT -> KotlinSpringClientBinding()
            else -> KotlinSpringBinding()
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
        val binding = binding(role)
        val pathItem = stringPathItemEntry.value
        val path = stringPathItemEntry.key
        val httpMethod = operationEntry.key
        val operation = operationEntry.value

        val methodAnnotations = binding.methodAnnotations(httpMethod, path, operation)
        check(methodAnnotations.isNotEmpty()) { unsupportedHttpMethod(httpMethod, path) }

        val methodBuilder = FunSpec
            .builder(operationId)
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        methodAnnotations.forEach(methodBuilder::addAnnotation)
        //we are deriving the returning type from the schema of the successful result
        binding.applyReturn(
            methodBuilder,
            determineReturnKotlinType(operation, openAPI, classBuilder, binding),
            generateResponseParameter
        )

        requestBodyParams(operation, openAPI, classBuilder, binding)
            .forEach { paramSpec: RequestPartParams ->
                val pb = ParameterSpec.builder(CaseUtils.toIdentifier(paramSpec.name), paramSpec.typeName)
                paramSpec.annotation?.let(pb::addAnnotation)
                methodBuilder.addParameter(pb.build())
            }

        // Order is part of the generated contract: body, then path, query, header.
        addParameters(methodBuilder, openAPI, classBuilder, pathItem, operation, "path") { parameter, _ ->
            binding.pathParamAnnotations(parameter)
        }
        addParameters(
            methodBuilder, openAPI, classBuilder, pathItem, operation, "query", CaseUtils::snakeToCamel
        ) { parameter, defaultValue ->
            binding.queryParamAnnotations(parameter, defaultValue)
        }
        addParameters(
            methodBuilder, openAPI, classBuilder, pathItem, operation, "header", CaseUtils::kebabToCamel
        ) { parameter, defaultValue ->
            binding.headerParamAnnotations(parameter, defaultValue)
        }

        binding.addContextParameters(methodBuilder, operation, role, generateResponseParameter)
        classBuilder.addFunction(methodBuilder.build())
    }

    /**
     * Adds every parameter declared `in` the given position, with the annotations
     * the binding gives it.
     */
    private fun addParameters(
        methodBuilder: FunSpec.Builder,
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        pathItem: PathItem,
        operation: Operation,
        `in`: String,
        identifier: (String) -> String = CaseUtils::snakeToCamel,
        annotations: (Parameter, String?) -> List<AnnotationSpec>
    ) {
        getParameterStream(pathItem, operation)
            .filter { parameter: Parameter -> `in`.equals(parameter.getIn(), ignoreCase = true) }
            .forEach { parameter: Parameter ->
                val pb = ParameterSpec.builder(
                    CaseUtils.toIdentifier(identifier(parameter.name)),
                    parameterType(parameter, openAPI, classBuilder),
                )
                annotations(parameter, typeDefiner.effectiveDefault(parameter.schema, openAPI))
                    .forEach(pb::addAnnotation)
                methodBuilder.addParameter(pb.build())
            }
    }

    /**
     * The error raised for an operation whose verb the target framework has no
     * mapping for.
     *
     * Generating the function without its verb annotation is the one thing that
     * must not happen: the result compiles, so nothing complains, and the
     * endpoint is simply never routed — a failure the user meets in production
     * rather than in the build. Support for a verb is a feature this generator
     * does not have yet, and saying so is what lets the user either drop the
     * operation or add it.
     */
    private fun unsupportedHttpMethod(httpMethod: PathItem.HttpMethod, path: String): String =
        "Unsupported HTTP method '${httpMethod.name.lowercase(Locale.ROOT)}' at path '$path': " +
            "hurdy-gurdy generates methods for get, post, put, patch and delete. Remove the " +
            "operation from the specification, or add support for the verb."

    /**
     * The Kotlin return type: the body of the successful reply, or `Unit` when
     * that reply has none.
     *
     * The body of a documented reply is there — a response `content` is what the
     * endpoint says it sends back — so the return type is non-null (`required =
     * true` below) unless the schema itself admits null. See
     * [isNullableParameter] for the same distinction on the way in.
     */
    private fun determineReturnKotlinType(
        operation: Operation,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        binding: KotlinFrameworkBinding
    ): TypeName =
        getSuccessfulReply(operation)
            .stream().asSequence()
            .flatMap { c: Content -> getContentType(c, openAPI, parent, binding, true) }
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
        binding: KotlinFrameworkBinding
    ): Sequence<RequestPartParams> {
        val body = operation.requestBody ?: return sequenceOf()
        val content = body.content ?: return sequenceOf()
        return getContentType(content, openAPI, parent, binding, body.required == true)
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
        binding: KotlinFrameworkBinding,
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
                            typeName = uploadType(schema, openAPI, binding)?.copy(nullable = nullable)
                                ?: typeDefiner.defineKotlinType(schema, openAPI, parent, null, nullable),
                            annotation = binding.multipartPartAnnotation(name, present)
                        )
                    }
            } else {
                //Single-part
                Optional.ofNullable(entry.value.schema).stream().asSequence()
                    // A binary single-part body/response is a converter-backed body
                    // type (Resource / InputStream), not a multipart part.
                    .map {
                        val nullable = !required || typeDefiner.isNullableType(it, openAPI)
                        if (isBinary(it)) binding.binaryBodyType().copy(nullable = nullable)
                        else typeDefiner.defineKotlinType(it, openAPI, parent, null, nullable)
                    }
                    .map {
                        RequestPartParams(
                            name = "request",
                            typeName = it,
                            annotation = binding.bodyAnnotation(required)
                        )
                    }
            }
        }
    }

    /** Whether a schema is `type: string, format: binary` (OpenAPI 3.0 or 3.1). */
    private fun isBinary(schema: Schema<*>?): Boolean {
        if (schema == null) {
            return false
        }
        return "string" == SchemaSemantics.effectiveType(schema) && "binary" == schema.format
    }

    /**
     * The upload type of a binary multipart part, or null when the part is not
     * binary at all: `MultipartFile` / `FileUpload` for a scalar
     * `format: binary`, a `List` of it for an array of them (a part sent several
     * times over), whether that array is spelled out or named by a reusable
     * alias.
     *
     * An array had to be spelled out here: a bare binary schema means
     * `ByteArray` to the type definer, which is right for a base64 property of a
     * JSON DTO but never for a multipart part.
     */
    private fun uploadType(
        schema: Schema<*>?,
        openAPI: OpenAPI,
        binding: KotlinFrameworkBinding
    ): TypeName? {
        if (isBinary(schema)) {
            return binding.multipartPartType()
        }
        if (schema == null) {
            return null
        }
        val `$ref` = schema.`$ref`
        if (`$ref` != null) {
            // A part may NAME the array (files: $ref FileList) rather than spell it
            // out. A same-file array alias is inlined at every point of use, so such
            // a part is the same repeated upload and has to be looked through here
            // as well. Under generateAliasAsModel the alias stays a class of its own
            // - inlinableArrayAlias returns null and the part keeps that class, as
            // it does everywhere else.
            val aliasTarget = typeDefiner.inlinableArrayAlias(`$ref`, openAPI) ?: return null
            return typeDefiner.inliningAlias<TypeName?>(`$ref`) { uploadType(aliasTarget, openAPI, binding) }
        }
        if (SchemaSemantics.isArraySchema(schema)) {
            val items = schema.items
            val itemType = uploadType(items, openAPI, binding) ?: return null
            return LIST.parameterizedBy(
                itemType.copy(nullable = typeDefiner.isNullableType(items, openAPI))
            )
        }
        return null
    }

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

    private companion object {
        val MP_REGISTER_REST_CLIENT =
            ClassName("org.eclipse.microprofile.rest.client.inject", "RegisterRestClient")
    }
}
