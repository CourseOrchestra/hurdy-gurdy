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
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Web MVC, server side: the {@code @GetMapping} family, and the
 * {@code HttpServletRequest}/{@code HttpServletResponse} handles a controller
 * may ask for.
 *
 * <p>Shared by {@link Role#CONTROLLER} and {@link Role#API}, which differ only
 * in whether response artifacts are requested at all — a question the extractor
 * answers before it gets here. The Spring <em>client</em> speaks a different
 * dialect and has its own binding.
 */
final class JavaSpringBinding implements JavaFrameworkBinding {

    static final ClassName MULTIPART_FILE =
            ClassName.get("org.springframework.web.multipart", "MultipartFile");
    static final ClassName SPRING_RESOURCE =
            ClassName.get("org.springframework.core.io", "Resource");

    @Override
    public List<AnnotationSpec> methodAnnotations(PathItem.HttpMethod httpMethod, String path,
                                                  Operation operation) {
        Class<?> annotationClass = switch (httpMethod) {
            case GET -> GetMapping.class;
            case POST -> PostMapping.class;
            case PUT -> PutMapping.class;
            case PATCH -> PatchMapping.class;
            case DELETE -> DeleteMapping.class;
            default -> null;
        };
        if (annotationClass == null) {
            return List.of();
        }
        AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                .addMember("value", "$S", path);
        APIExtractor.getSuccessfulReply(operation)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .ifPresent(mt -> builder.addMember("produces", "$S", mt));
        Optional.ofNullable(operation.getRequestBody())
                .map(RequestBody::getContent)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                // application/json is Spring's own default, so saying it adds nothing.
                .filter(s -> !s.isBlank() && !s.equals("application/json"))
                .ifPresent(mt -> builder.addMember("consumes", "$S", mt));
        return List.of(builder.build());
    }

    @Override
    public List<AnnotationSpec> pathParamAnnotations(Parameter parameter) {
        return List.of(AnnotationSpec.builder(PathVariable.class)
                .addMember("name", "$S", parameter.getName()).build());
    }

    @Override
    public List<AnnotationSpec> queryParamAnnotations(Parameter parameter, String defaultValue) {
        return List.of(springParam(RequestParam.class, parameter, defaultValue));
    }

    @Override
    public List<AnnotationSpec> headerParamAnnotations(Parameter parameter, String defaultValue) {
        return List.of(springParam(RequestHeader.class, parameter, defaultValue));
    }

    /**
     * Spring states everything about a bound parameter in one annotation:
     * whether it is required, its name on the wire, and its default when it has
     * one.
     */
    static AnnotationSpec springParam(Class<?> annotationClass, Parameter parameter, String defaultValue) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                .addMember("required", "$L", parameter.getRequired())
                .addMember("name", "$S", parameter.getName());
        if (defaultValue != null) {
            builder.addMember("defaultValue", "$S", defaultValue);
        }
        return builder.build();
    }

    @Override
    public AnnotationSpec bodyAnnotation() {
        return AnnotationSpec.builder(org.springframework.web.bind.annotation.RequestBody.class).build();
    }

    @Override
    public AnnotationSpec multipartPartAnnotation(String partName) {
        return AnnotationSpec.builder(RequestPart.class).addMember("name", "$S", partName).build();
    }

    @Override
    public ClassName multipartPartType() {
        return MULTIPART_FILE;
    }

    @Override
    public ClassName binaryBodyType() {
        return SPRING_RESOURCE;
    }

    @Override
    public void applyReturn(MethodSpec.Builder method, TypeName dtoReturn,
                            boolean generateResponseParameter) {
        // A Spring controller returns the DTO itself; the response is reached
        // through the HttpServletResponse parameter instead.
        method.returns(dtoReturn);
    }

    @Override
    public void addContextParameters(MethodSpec.Builder method, Operation operation, Role role,
                                     boolean generateResponseParameter) {
        if (!generateResponseParameter) {
            return;
        }
        if (APIExtractor.isIncludeRequest(operation)) {
            method.addParameter(ParameterSpec.builder(HttpServletRequest.class, "request").build());
        }
        method.addParameter(ParameterSpec.builder(HttpServletResponse.class, "response").build());
    }
}
