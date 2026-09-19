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

package ru.curs.hurdygurdy.extract

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
import io.swagger.v3.oas.models.media.Schema
import ru.curs.hurdygurdy.CaseUtils
import ru.curs.hurdygurdy.CaseUtils.normalizeToCamel
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.GeneratorParams
import ru.curs.hurdygurdy.Role
import ru.curs.hurdygurdy.binding.KotlinFrameworkBinding
import ru.curs.hurdygurdy.binding.KotlinQuarkusBinding
import ru.curs.hurdygurdy.binding.KotlinSpringBinding
import ru.curs.hurdygurdy.binding.KotlinSpringClientBinding
import ru.curs.hurdygurdy.emit.KotlinTypeDefiner
import ru.curs.hurdygurdy.model.ApiModelBuilder
import ru.curs.hurdygurdy.model.BodyModel
import ru.curs.hurdygurdy.model.OperationModel
import ru.curs.hurdygurdy.model.ParameterModel
import ru.curs.hurdygurdy.spec.SchemaSemantics
import java.util.*
import kotlin.streams.asSequence

/**
 * Generates one Kotlin interface per OpenAPI tag, with one function per
 * operation.
 *
 * One method-building algorithm; everything that changes between Spring, the
 * Spring HTTP interface and Quarkus is supplied by a [KotlinFrameworkBinding].
 */
class KotlinAPIExtractor(
    private val typeDefiner: KotlinTypeDefiner,
    params: GeneratorParams
) :
    APIExtractor<TypeSpec, TypeSpec.Builder>(
        params,
        ApiModelBuilder(typeDefiner),
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
        operation: OperationModel,
        role: Role,
        generateResponseParameter: Boolean
    ) {
        val binding = binding(role)

        val methodAnnotations = binding.methodAnnotations(operation)
        check(methodAnnotations.isNotEmpty()) { unsupportedHttpMethod(operation) }

        val methodBuilder = FunSpec
            .builder(operation.id())
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        methodAnnotations.forEach(methodBuilder::addAnnotation)
        //we are deriving the returning type from the schema of the successful result
        binding.applyReturn(
            methodBuilder,
            determineReturnKotlinType(operation.response(), openAPI, classBuilder, binding),
            generateResponseParameter
        )

        bodyParameters(operation.body(), openAPI, classBuilder, binding)
            .forEach { paramSpec: RequestPartParams ->
                val pb = ParameterSpec.builder(CaseUtils.toIdentifier(paramSpec.name), paramSpec.typeName)
                paramSpec.annotation?.let(pb::addAnnotation)
                methodBuilder.addParameter(pb.build())
            }

        // The model has already put these in the order the signature declares
        // them: path, then query, then header.
        operation.parameters().forEach { parameter ->
            methodBuilder.addParameter(parameter(parameter, openAPI, classBuilder, binding))
        }

        binding.addContextParameters(
            methodBuilder, operation.includeRequest(), role, generateResponseParameter
        )
        classBuilder.addFunction(methodBuilder.build())
    }

    /** One bound parameter, with the annotations the binding gives its position. */
    private fun parameter(
        parameter: ParameterModel,
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        binding: KotlinFrameworkBinding
    ): ParameterSpec {
        val pb = ParameterSpec.builder(
            parameter.identifier(),
            typeDefiner.defineKotlinType(
                parameter.schema(), openAPI, classBuilder, null, !parameter.present()
                    || typeDefiner.isNullableType(parameter.schema(), openAPI)
            ),
        )
        when (parameter.`in`()) {
            ParameterModel.In.PATH -> binding.pathParamAnnotations(parameter)
            ParameterModel.In.QUERY -> binding.queryParamAnnotations(parameter)
            ParameterModel.In.HEADER -> binding.headerParamAnnotations(parameter)
        }.forEach(pb::addAnnotation)
        return pb.build()
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
    private fun unsupportedHttpMethod(operation: OperationModel): String =
        "Unsupported HTTP method '${operation.httpMethod().name.lowercase(Locale.ROOT)}' " +
            "at path '${operation.path()}': " +
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
        response: BodyModel?,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        binding: KotlinFrameworkBinding
    ): TypeName =
        bodyParameters(response, openAPI, parent, binding)
            .map { it.typeName }
            .firstOrNull() ?: UNIT

    private data class RequestPartParams(
        val typeName: TypeName,
        val name: String,
        val annotation: AnnotationSpec?
    )

    /**
     * The parameters a body contributes to the signature: one per part when it is
     * multipart, otherwise the single `request` parameter — or none at all when
     * the media type declares no schema.
     */
    private fun bodyParameters(
        body: BodyModel?,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        binding: KotlinFrameworkBinding
    ): Sequence<RequestPartParams> {
        if (body == null) {
            return sequenceOf()
        }
        if (body.multipart()) {
            return body.parts().asSequence().map { part ->
                val nullable = !part.present() || typeDefiner.isNullableType(part.schema(), openAPI)
                RequestPartParams(
                    name = part.name(),
                    // A binary part, or an array of them, is an uploaded file
                    // (MultipartFile / FileUpload); other parts resolve normally.
                    typeName = uploadType(part.schema(), openAPI, binding)?.copy(nullable = nullable)
                        ?: typeDefiner.defineKotlinType(part.schema(), openAPI, parent, null, nullable),
                    annotation = binding.multipartPartAnnotation(part)
                )
            }
        }
        return Optional.ofNullable(body.schema()).stream().asSequence()
            // A binary single-part body/response is a converter-backed body type
            // (Resource / InputStream), not a multipart part.
            .map {
                val nullable = !body.required() || typeDefiner.isNullableType(it, openAPI)
                if (isBinary(it)) binding.binaryBodyType().copy(nullable = nullable)
                else typeDefiner.defineKotlinType(it, openAPI, parent, null, nullable)
            }
            .map {
                RequestPartParams(
                    name = "request",
                    typeName = it,
                    annotation = binding.bodyAnnotation(body.required())
                )
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
            return typeDefiner.inliningAlias(`$ref`) { uploadType(aliasTarget, openAPI, binding) }
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


    private companion object {
        val MP_REGISTER_REST_CLIENT =
            ClassName("org.eclipse.microprofile.rest.client.inject", "RegisterRestClient")
    }
}
