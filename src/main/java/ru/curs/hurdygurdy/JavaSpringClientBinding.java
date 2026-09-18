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
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring's declarative HTTP interface: the {@code @GetExchange} family, which a
 * {@code HttpServiceProxyFactory} (or OpenFeign) turns into a working client.
 *
 * <p>A binding rather than a role special case. It shares Spring's parameter
 * annotations — {@code @PathVariable}, {@code @RequestParam},
 * {@code @RequestHeader} are the same on both sides of the wire — but maps the
 * method with a different annotation family and wraps its return type in
 * {@code ResponseEntity} rather than handing the caller a servlet response.
 */
final class JavaSpringClientBinding implements JavaFrameworkBinding {

    private static final ClassName SPRING_GET_EXCHANGE =
            ClassName.get("org.springframework.web.service.annotation", "GetExchange");
    private static final ClassName SPRING_POST_EXCHANGE =
            ClassName.get("org.springframework.web.service.annotation", "PostExchange");
    private static final ClassName SPRING_PUT_EXCHANGE =
            ClassName.get("org.springframework.web.service.annotation", "PutExchange");
    private static final ClassName SPRING_PATCH_EXCHANGE =
            ClassName.get("org.springframework.web.service.annotation", "PatchExchange");
    private static final ClassName SPRING_DELETE_EXCHANGE =
            ClassName.get("org.springframework.web.service.annotation", "DeleteExchange");
    private static final ClassName SPRING_RESPONSE_ENTITY =
            ClassName.get("org.springframework.http", "ResponseEntity");

    private final JavaSpringBinding parameters = new JavaSpringBinding();

    @Override
    public List<AnnotationSpec> methodAnnotations(PathItem.HttpMethod httpMethod, String path,
                                                  Operation operation) {
        ClassName annotationClass = switch (httpMethod) {
            case GET -> SPRING_GET_EXCHANGE;
            case POST -> SPRING_POST_EXCHANGE;
            case PUT -> SPRING_PUT_EXCHANGE;
            case PATCH -> SPRING_PATCH_EXCHANGE;
            case DELETE -> SPRING_DELETE_EXCHANGE;
            default -> null;
        };
        if (annotationClass == null) {
            return List.of();
        }
        AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                .addMember("value", "$S", path);
        // The client states what it will accept, where the server states what it
        // produces: the same media type read from the other end.
        APIExtractor.getSuccessfulReply(operation)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .ifPresent(mt -> builder.addMember("accept", "$S", mt));
        Optional.ofNullable(operation.getRequestBody())
                .map(RequestBody::getContent)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .filter(s -> !s.isBlank() && !s.equals("application/json"))
                .ifPresent(mt -> builder.addMember("contentType", "$S", mt));
        return List.of(builder.build());
    }

    @Override
    public List<AnnotationSpec> pathParamAnnotations(io.swagger.v3.oas.models.parameters.Parameter parameter) {
        return parameters.pathParamAnnotations(parameter);
    }

    @Override
    public List<AnnotationSpec> queryParamAnnotations(io.swagger.v3.oas.models.parameters.Parameter parameter,
                                                      String defaultValue) {
        return parameters.queryParamAnnotations(parameter, defaultValue);
    }

    @Override
    public List<AnnotationSpec> headerParamAnnotations(io.swagger.v3.oas.models.parameters.Parameter parameter,
                                                       String defaultValue) {
        return parameters.headerParamAnnotations(parameter, defaultValue);
    }

    @Override
    public AnnotationSpec bodyAnnotation() {
        return parameters.bodyAnnotation();
    }

    @Override
    public AnnotationSpec multipartPartAnnotation(String partName) {
        return parameters.multipartPartAnnotation(partName);
    }

    @Override
    public ClassName multipartPartType() {
        return parameters.multipartPartType();
    }

    @Override
    public ClassName binaryBodyType() {
        return parameters.binaryBodyType();
    }

    @Override
    public void applyReturn(MethodSpec.Builder method, TypeName dtoReturn,
                            boolean generateResponseParameter) {
        if (generateResponseParameter) {
            // A client has no servlet response to be handed, so the status and
            // headers it was asked for come back inside a ResponseEntity.
            TypeName body = dtoReturn.equals(TypeName.VOID) ? ClassName.get(Void.class) : dtoReturn.box();
            method.returns(ParameterizedTypeName.get(SPRING_RESPONSE_ENTITY, body));
        } else {
            method.returns(dtoReturn);
        }
    }

    @Override
    public void addContextParameters(MethodSpec.Builder method, Operation operation, Role role,
                                     boolean generateResponseParameter) {
        // A client interface takes no server-side context handles.
    }
}
