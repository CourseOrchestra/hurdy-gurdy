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
import com.palantir.javapoet.TypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Quarkus, through JAX-RS: one annotation per verb plus a separate
 * {@code @Path}, and a {@code @DefaultValue} that sits beside the parameter
 * annotation rather than inside it.
 *
 * <p>One binding for every role. A Quarkus client differs from a Quarkus
 * resource only by the interface-level {@code @RegisterRestClient}, which the
 * extractor adds, and by having no {@code @Context} parameter — hence the
 * {@link Role} test in {@link #addContextParameters}, the one place where this
 * framework distinguishes them.
 */
final class JavaQuarkusBinding implements JavaFrameworkBinding {

    static final ClassName JAXRS_PATH = ClassName.get("jakarta.ws.rs", "Path");
    private static final ClassName JAXRS_GET = ClassName.get("jakarta.ws.rs", "GET");
    private static final ClassName JAXRS_POST = ClassName.get("jakarta.ws.rs", "POST");
    private static final ClassName JAXRS_PUT = ClassName.get("jakarta.ws.rs", "PUT");
    private static final ClassName JAXRS_PATCH = ClassName.get("jakarta.ws.rs", "PATCH");
    private static final ClassName JAXRS_DELETE = ClassName.get("jakarta.ws.rs", "DELETE");
    private static final ClassName JAXRS_PRODUCES = ClassName.get("jakarta.ws.rs", "Produces");
    private static final ClassName JAXRS_CONSUMES = ClassName.get("jakarta.ws.rs", "Consumes");
    private static final ClassName JAXRS_PATH_PARAM = ClassName.get("jakarta.ws.rs", "PathParam");
    private static final ClassName JAXRS_QUERY_PARAM = ClassName.get("jakarta.ws.rs", "QueryParam");
    private static final ClassName JAXRS_DEFAULT_VALUE = ClassName.get("jakarta.ws.rs", "DefaultValue");
    private static final ClassName JAXRS_HEADER_PARAM = ClassName.get("jakarta.ws.rs", "HeaderParam");
    private static final ClassName JAXRS_CONTEXT = ClassName.get("jakarta.ws.rs.core", "Context");
    private static final ClassName JAXRS_RESPONSE = ClassName.get("jakarta.ws.rs.core", "Response");
    private static final ClassName JAXRS_REQUEST_CONTEXT =
            ClassName.get("jakarta.ws.rs.container", "ContainerRequestContext");
    private static final ClassName QUARKUS_REST_FORM =
            ClassName.get("org.jboss.resteasy.reactive", "RestForm");
    private static final ClassName QUARKUS_FILE_UPLOAD =
            ClassName.get("org.jboss.resteasy.reactive.multipart", "FileUpload");
    private static final ClassName INPUT_STREAM = ClassName.get(java.io.InputStream.class);

    @Override
    public List<AnnotationSpec> methodAnnotations(OperationModel operation) {
        ClassName verb = switch (operation.httpMethod()) {
            case GET -> JAXRS_GET;
            case POST -> JAXRS_POST;
            case PUT -> JAXRS_PUT;
            case PATCH -> JAXRS_PATCH;
            case DELETE -> JAXRS_DELETE;
            default -> null;
        };
        if (verb == null) {
            return List.of();
        }
        List<AnnotationSpec> result = new ArrayList<>();
        result.add(AnnotationSpec.builder(verb).build());
        result.add(AnnotationSpec.builder(JAXRS_PATH)
                .addMember("value", "$S", operation.path()).build());
        Optional.ofNullable(operation.response())
                .map(BodyModel::mediaType)
                .ifPresent(mt -> result.add(AnnotationSpec.builder(JAXRS_PRODUCES)
                        .addMember("value", "$S", mt).build()));
        // Unlike Spring's `consumes`, application/json is NOT filtered out: JAX-RS
        // has no such default, so leaving it out would widen what the resource accepts.
        Optional.ofNullable(operation.body())
                .map(BodyModel::mediaType)
                .filter(mt -> !mt.isBlank())
                .ifPresent(mt -> result.add(AnnotationSpec.builder(JAXRS_CONSUMES)
                        .addMember("value", "$S", mt).build()));
        return result;
    }

    @Override
    public List<AnnotationSpec> pathParamAnnotations(ParameterModel parameter) {
        return List.of(jaxrsParam(JAXRS_PATH_PARAM, parameter));
    }

    @Override
    public List<AnnotationSpec> queryParamAnnotations(ParameterModel parameter) {
        return withDefault(jaxrsParam(JAXRS_QUERY_PARAM, parameter), parameter.defaultValue());
    }

    @Override
    public List<AnnotationSpec> headerParamAnnotations(ParameterModel parameter) {
        return withDefault(jaxrsParam(JAXRS_HEADER_PARAM, parameter), parameter.defaultValue());
    }

    private static AnnotationSpec jaxrsParam(ClassName annotationClass, ParameterModel parameter) {
        return AnnotationSpec.builder(annotationClass)
                .addMember("value", "$S", parameter.specName()).build();
    }

    /**
     * JAX-RS has no {@code defaultValue} member; the default is a separate
     * annotation on the same parameter, and there is nowhere to state
     * {@code required} at all.
     */
    private static List<AnnotationSpec> withDefault(AnnotationSpec param, String defaultValue) {
        if (defaultValue == null) {
            return List.of(param);
        }
        return List.of(param, AnnotationSpec.builder(JAXRS_DEFAULT_VALUE)
                .addMember("value", "$S", defaultValue).build());
    }

    @Override
    public AnnotationSpec bodyAnnotation() {
        // JAX-RS infers the entity from the signature: the one unannotated parameter.
        return null;
    }

    @Override
    public AnnotationSpec multipartPartAnnotation(PartModel part) {
        return AnnotationSpec.builder(QUARKUS_REST_FORM)
                .addMember("value", "$S", part.name()).build();
    }

    @Override
    public ClassName multipartPartType() {
        return QUARKUS_FILE_UPLOAD;
    }

    @Override
    public ClassName binaryBodyType() {
        return INPUT_STREAM;
    }

    @Override
    public void applyReturn(MethodSpec.Builder method, TypeName dtoReturn,
                            boolean generateResponseParameter) {
        if (generateResponseParameter) {
            // JAX-RS carries status and headers in the return value, so the DTO the
            // caller should put in it is recorded in the javadoc instead.
            method.returns(JAXRS_RESPONSE);
            method.addJavadoc("@return a $T whose entity is expected to be $L\n",
                    JAXRS_RESPONSE,
                    dtoReturn.equals(TypeName.VOID) ? "empty (no body)" : dtoReturn.toString());
        } else {
            method.returns(dtoReturn);
        }
    }

    @Override
    public void addContextParameters(MethodSpec.Builder method, boolean includeRequest, Role role,
                                     boolean generateResponseParameter) {
        if (generateResponseParameter && includeRequest && role == Role.CONTROLLER) {
            method.addParameter(ParameterSpec.builder(JAXRS_REQUEST_CONTEXT, "requestContext")
                    .addAnnotation(JAXRS_CONTEXT).build());
        }
    }
}
