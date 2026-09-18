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

package ru.curs.hurdygurdy;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import javax.lang.model.element.Modifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static ru.curs.hurdygurdy.CaseUtils.normalizeToCamel;

/**
 * Generates one Java interface per OpenAPI tag, with one method per operation.
 *
 * <p>There is a single method-building algorithm here; everything that changes
 * between Spring, the Spring HTTP interface and Quarkus is supplied by a
 * {@link JavaFrameworkBinding}. It used to be three copies of that algorithm,
 * one per dialect, which is how they came to disagree about things that are not
 * dialect at all — see {@code ApiParityTest}.
 */
public class JavaAPIExtractor extends APIExtractor<TypeSpec, TypeSpec.Builder> {

    private static final ClassName MP_REGISTER_REST_CLIENT =
            ClassName.get("org.eclipse.microprofile.rest.client.inject", "RegisterRestClient");

    private final JavaTypeDefiner typeDefiner;
    private final Framework framework;

    public JavaAPIExtractor(JavaTypeDefiner typeDefiner,
                            GeneratorParams params) {
        super(params,
                new ApiModelBuilder(typeDefiner),
                (name, role) -> {
                    TypeSpec.Builder b = TypeSpec.interfaceBuilder(normalizeToCamel(name));
                    if (params.getFramework() == Framework.QUARKUS) {
                        b.addAnnotation(AnnotationSpec.builder(JavaQuarkusBinding.JAXRS_PATH)
                                .addMember("value", "$S", "").build());
                        if (role == Role.CLIENT) {
                            b.addAnnotation(AnnotationSpec.builder(MP_REGISTER_REST_CLIENT).build());
                        }
                    }
                    return b;
                },
                b -> {
                    b.addModifiers(Modifier.PUBLIC);
                    return b.build();
                });
        this.typeDefiner = typeDefiner;
        this.framework = params.getFramework();
    }

    /**
     * The annotation dialect to generate this role in. Quarkus speaks one for
     * every role; Spring speaks two, and which of them applies is decided here
     * rather than inside the algorithm.
     */
    private JavaFrameworkBinding binding(Role role) {
        if (framework == Framework.QUARKUS) {
            return new JavaQuarkusBinding();
        }
        return role == Role.CLIENT ? new JavaSpringClientBinding() : new JavaSpringBinding();
    }

    @Override
    void buildMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder, OperationModel operation,
                     Role role, boolean generateResponseParameter) {
        JavaFrameworkBinding binding = binding(role);

        List<AnnotationSpec> methodAnnotations = binding.methodAnnotations(operation);
        if (methodAnnotations.isEmpty()) {
            throw new IllegalStateException(unsupportedHttpMethod(operation));
        }

        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(operation.id())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        methodAnnotations.forEach(methodBuilder::addAnnotation);
        //we are deriving the returning type from the schema of the successful result
        binding.applyReturn(methodBuilder,
                determineReturnJavaType(operation.response(), openAPI, classBuilder, binding),
                generateResponseParameter);

        bodyParameters(operation.body(), openAPI, classBuilder, binding)
                .forEach(paramSpec -> {
                    ParameterSpec.Builder pb = ParameterSpec.builder(
                            paramSpec.typeName, CaseUtils.toIdentifier(paramSpec.name));
                    if (paramSpec.annotation != null) {
                        pb.addAnnotation(paramSpec.annotation);
                    }
                    methodBuilder.addParameter(pb.build());
                });

        // The model has already put these in the order the signature declares
        // them: path, then query, then header.
        for (ParameterModel parameter : operation.parameters()) {
            methodBuilder.addParameter(parameter(parameter, openAPI, classBuilder, binding));
        }

        binding.addContextParameters(methodBuilder, operation.includeRequest(), role,
                generateResponseParameter);
        classBuilder.addMethod(methodBuilder.build());
    }

    /**
     * One bound parameter.
     *
     * <p>A path variable is part of the URL and therefore always present, so it
     * is unboxed to keep the signature compact; a query or header parameter may
     * be absent and must be able to arrive null, so it is boxed.
     */
    private ParameterSpec parameter(ParameterModel parameter, OpenAPI openAPI,
                                    TypeSpec.Builder classBuilder, JavaFrameworkBinding binding) {
        TypeName type = typeDefiner.defineJavaType(parameter.schema(), openAPI, classBuilder, null);
        ParameterSpec.Builder pb = ParameterSpec.builder(
                parameter.in() == ParameterModel.In.PATH ? safeUnbox(type) : safeBox(type),
                parameter.identifier());
        switch (parameter.in()) {
            case PATH -> binding.pathParamAnnotations(parameter).forEach(pb::addAnnotation);
            case QUERY -> binding.queryParamAnnotations(parameter).forEach(pb::addAnnotation);
            case HEADER -> binding.headerParamAnnotations(parameter).forEach(pb::addAnnotation);
            default -> throw new IllegalStateException("Unknown parameter position " + parameter.in());
        }
        return pb.build();
    }

    /**
     * The error raised for an operation whose verb the target framework has no
     * mapping for.
     *
     * <p>Generating the method without its verb annotation is the one thing that
     * must not happen: the result compiles, so nothing complains, and the
     * endpoint is simply never routed — a failure the user meets in production
     * rather than in the build. Support for a verb is a feature this generator
     * does not have yet, and saying so is what lets the user either drop the
     * operation or add it.
     */
    private static String unsupportedHttpMethod(OperationModel operation) {
        return String.format(
                "Unsupported HTTP method '%s' at path '%s': hurdy-gurdy generates methods for "
                        + "get, post, put, patch and delete. Remove the operation from the "
                        + "specification, or add support for the verb.",
                operation.httpMethod().name().toLowerCase(Locale.ROOT), operation.path());
    }

    private static TypeName safeBox(TypeName name) {
        return name.isPrimitive() ? name.box() : name;
    }

    private static TypeName safeUnbox(TypeName name) {
        return name.isBoxedPrimitive() ? name.unbox() : name;
    }

    /**
     * The type of a request/response body or a multipart part. Primitives are
     * unboxed to keep signatures compact ({@code int getBills()}), <em>unless</em>
     * the schema admits null (3.0 {@code nullable: true}, 3.1
     * {@code type: [X, "null"]}) — a primitive cannot carry the null the schema
     * declares legal, and unlike a path variable a body may genuinely be absent.
     */
    private static TypeName bodyTypeName(Schema<?> schema, TypeName name) {
        return SchemaSemantics.isNullableSchema(schema) ? safeBox(name) : safeUnbox(name);
    }

    private TypeName determineReturnJavaType(BodyModel response, OpenAPI openAPI,
                                             TypeSpec.Builder parent, JavaFrameworkBinding binding) {
        return bodyParameters(response, openAPI, parent, binding)
                .map(p -> p.typeName)
                .findFirst()
                .orElse(TypeName.VOID);
    }

    private static class RequestPartParams {
        final TypeName typeName;
        final String name;
        final AnnotationSpec annotation;

        RequestPartParams(TypeName typeName, String name, AnnotationSpec annotation) {
            this.typeName = typeName;
            this.name = name;
            this.annotation = annotation;
        }
    }

    /**
     * The parameters a body contributes to the signature: one per part when it is
     * multipart, otherwise the single {@code request} parameter — or none at all
     * when the media type declares no schema.
     */
    private Stream<RequestPartParams> bodyParameters(BodyModel body, OpenAPI openAPI,
                                                     TypeSpec.Builder parent,
                                                     JavaFrameworkBinding binding) {
        if (body == null) {
            return Stream.of();
        }
        if (body.multipart()) {
            return body.parts().stream()
                    .map(part -> new RequestPartParams(
                            multipartPartTypeName(part.schema(), openAPI, parent, binding),
                            part.name(),
                            binding.multipartPartAnnotation(part)));
        }
        return Optional.ofNullable(body.schema()).stream()
                // A binary single-part body/response is a converter-backed
                // body type (Resource / InputStream), not a multipart part.
                .map(s -> isBinary(s)
                        ? binding.binaryBodyType()
                        : JavaAPIExtractor.bodyTypeName(s,
                                typeDefiner.defineJavaType(s, openAPI, parent, null)))
                .map(t -> new RequestPartParams(t, "request", binding.bodyAnnotation()));
    }

    /**
     * The type of a single multipart part: an uploaded file when the part is
     * binary, the part's own type otherwise.
     */
    private TypeName multipartPartTypeName(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder parent,
                                           JavaFrameworkBinding binding) {
        TypeName upload = uploadType(schema, openAPI, binding);
        return upload != null
                ? upload
                : JavaAPIExtractor.bodyTypeName(schema,
                        typeDefiner.defineJavaType(schema, openAPI, parent, null));
    }

    /**
     * The upload type of a binary multipart part, or null when the part is not
     * binary at all: {@code MultipartFile} / {@code FileUpload} for a scalar
     * {@code format: binary}, a {@code List} of it for an array of them (a part
     * sent several times over), whether that array is spelled out or named by a
     * reusable alias.
     *
     * <p>An array had to be spelled out here: a bare binary schema means
     * {@code byte[]} to the type definer, which is right for a base64 property
     * of a JSON DTO but never for a multipart part.
     */
    private TypeName uploadType(Schema<?> schema, OpenAPI openAPI, JavaFrameworkBinding binding) {
        if (isBinary(schema)) {
            return binding.multipartPartType();
        }
        if (schema == null) {
            return null;
        }
        String ref = schema.get$ref();
        if (ref != null) {
            // A part may NAME the array (files: $ref FileList) rather than spell
            // it out. A same-file array alias is inlined at every point of use,
            // so such a part is the same repeated upload and has to be looked
            // through here as well. Under generateAliasAsModel the alias stays a
            // class of its own — inlinableArrayAlias returns null and the part
            // keeps that class, as it does everywhere else.
            Schema<?> aliasTarget = typeDefiner.inlinableArrayAlias(ref, openAPI);
            return aliasTarget == null
                    ? null
                    : typeDefiner.inliningAlias(ref, () -> uploadType(aliasTarget, openAPI, binding));
        }
        if (SchemaSemantics.isArraySchema(schema)) {
            TypeName itemType = uploadType(schema.getItems(), openAPI, binding);
            if (itemType != null) {
                return ParameterizedTypeName.get(ClassName.get(List.class), itemType);
            }
        }
        return null;
    }

    /** Whether a schema is {@code type: string, format: binary} (OpenAPI 3.0 or 3.1). */
    private static boolean isBinary(Schema<?> schema) {
        return schema != null && "string".equals(SchemaSemantics.effectiveType(schema))
                && "binary".equals(schema.getFormat());
    }
}
