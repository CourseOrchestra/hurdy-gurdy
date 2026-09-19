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

package ru.curs.hurdygurdy.binding;

import ru.curs.hurdygurdy.Role;
import ru.curs.hurdygurdy.model.BodyModel;
import ru.curs.hurdygurdy.model.OperationModel;
import ru.curs.hurdygurdy.model.ParameterModel;
import ru.curs.hurdygurdy.model.PartModel;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import java.util.List;
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
public final class JavaSpringClientBinding implements JavaFrameworkBinding {

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
    public List<AnnotationSpec> methodAnnotations(OperationModel operation) {
        ClassName annotationClass = switch (operation.httpMethod()) {
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
                .addMember("value", "$S", operation.path());
        // The client states what it will accept, where the server states what it
        // produces: the same media type read from the other end.
        Optional.ofNullable(operation.response())
                .map(BodyModel::mediaType)
                .ifPresent(mt -> builder.addMember("accept", "$S", mt));
        Optional.ofNullable(operation.body())
                .map(BodyModel::mediaType)
                .filter(mt -> !mt.isBlank() && !mt.equals("application/json"))
                .ifPresent(mt -> builder.addMember("contentType", "$S", mt));
        return List.of(builder.build());
    }

    @Override
    public List<AnnotationSpec> pathParamAnnotations(ParameterModel parameter) {
        return parameters.pathParamAnnotations(parameter);
    }

    @Override
    public List<AnnotationSpec> queryParamAnnotations(ParameterModel parameter) {
        return parameters.queryParamAnnotations(parameter);
    }

    @Override
    public List<AnnotationSpec> headerParamAnnotations(ParameterModel parameter) {
        return parameters.headerParamAnnotations(parameter);
    }

    @Override
    public AnnotationSpec bodyAnnotation() {
        return parameters.bodyAnnotation();
    }

    @Override
    public AnnotationSpec multipartPartAnnotation(PartModel part) {
        return parameters.multipartPartAnnotation(part);
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
    public void addContextParameters(MethodSpec.Builder method, boolean includeRequest, Role role,
                                     boolean generateResponseParameter) {
        // A client interface takes no server-side context handles.
    }
}
