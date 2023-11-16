package ru.curs.hurdygurdy;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import javax.lang.model.element.Modifier;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class JavaAPIExtractor extends APIExtractor<TypeSpec, TypeSpec.Builder> {

    public JavaAPIExtractor(TypeDefiner<TypeSpec> typeDefiner,
                            GeneratorParams params) {
        super(typeDefiner, params,
                TypeSpec::interfaceBuilder,
                TypeSpec.Builder::build);
    }


    @Override
    void buildMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
                     Map.Entry<String, PathItem> stringPathItemEntry,
                     Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                     String operationId,
                     boolean generateResponseParameter) {
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(operationId)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        methodBuilder.addAnnotation(getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.getKey()));
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnJavaType(operationEntry.getValue(), openAPI, classBuilder));
        Optional.ofNullable(operationEntry.getValue().getRequestBody())
                .map(RequestBody::getContent)
                .stream()
                .flatMap(c -> getContentTypes(c, openAPI, classBuilder))
                .forEach(paramSpec ->
                        methodBuilder.addParameter(ParameterSpec.builder(
                                paramSpec.typeName,
                                paramSpec.name)
                                .addAnnotation(paramSpec.annotation).build())
                );

        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                        safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder, null)),
                        CaseUtils.snakeToCamel(parameter.getName()))
                        .addAnnotation(
                                AnnotationSpec.builder(PathVariable.class)
                                        .addMember("name", "$S", parameter.getName()).build()
                        )
                        .build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "query".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> {
                            AnnotationSpec.Builder builder = AnnotationSpec.builder(
                                    RequestParam.class
                            ).addMember("required", "$L", parameter.getRequired())
                                    .addMember("name", "$S", parameter.getName());

                            Optional.ofNullable(parameter.getSchema())
                                    .map(Schema::getDefault)
                                    .ifPresent(
                                            d -> builder.addMember("defaultValue", "$S", d.toString()));

                            AnnotationSpec annotationSpec = builder.build();
                            methodBuilder.addParameter(ParameterSpec.builder(
                                    safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI,
                                            classBuilder, null)),
                                    CaseUtils.snakeToCamel(parameter.getName()))
                                    .addAnnotation(annotationSpec).build());
                        }
                );
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "header".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                        safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder, null)),
                        CaseUtils.kebabToCamel(parameter.getName()))
                        .addAnnotation(
                                AnnotationSpec.builder(
                                        RequestHeader.class
                                ).addMember("required", "$L", parameter.getRequired())
                                        .addMember("name", "$S", parameter.getName()).build()
                        ).build()));
        if (generateResponseParameter) {
            methodBuilder.addParameter(ParameterSpec.builder(
                    HttpServletResponse.class,
                    "response").build());
        }
        classBuilder.addMethod(methodBuilder.build());
    }

    private static TypeName safeBox(TypeName name) {
        return name.isPrimitive() ? name.box() : name;
    }

    private static TypeName safeUnbox(TypeName name) {
        return name.isBoxedPrimitive() ? name.unbox() : name;
    }

    private TypeName determineReturnJavaType(Operation operation, OpenAPI openAPI, TypeSpec.Builder parent) {
        return getSuccessfulReply(operation)
                .stream()
                .flatMap(c -> getContentTypes(c, openAPI, parent))
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

    private Stream<RequestPartParams> getContentTypes(Content content, OpenAPI openAPI, TypeSpec.Builder parent) {
        final Optional<Map.Entry<String, MediaType>> mediaTypeEntry =
                Optional.ofNullable(content)
                        .<Map.Entry<String, MediaType>>flatMap(APIExtractor::getMediaType);
        if (mediaTypeEntry.isEmpty()) {
            return Stream.of();
        } else {
            Map.Entry<String, MediaType> entry = mediaTypeEntry.get();
            if ("multipart/form-data".equalsIgnoreCase(entry.getKey())) {
                //Multipart
                return ((Schema<?>) entry.getValue().getSchema())
                        .getProperties()
                        .entrySet()
                        .stream()
                        .map(e -> new RequestPartParams(
                                JavaAPIExtractor.safeUnbox(typeDefiner.defineJavaType(e.getValue(), openAPI,
                                        parent, null)),
                                e.getKey(),
                                AnnotationSpec.builder(RequestPart.class)
                                        .addMember("name", "$S", e.getKey()).build()));
            } else {
                //Single-part
                return Optional.ofNullable(entry.getValue().getSchema()).stream()
                        .map(s -> typeDefiner.defineJavaType(s, openAPI, parent, null))
                        .map(JavaAPIExtractor::safeUnbox).map(t ->
                                new RequestPartParams(t,
                                        "request",
                                        AnnotationSpec.builder(
                                                org.springframework.web.bind.annotation.RequestBody.class).build()));
            }
        }
    }

    private AnnotationSpec getControllerMethodAnnotationSpec(Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                                                             String path) {
        Class<?> annotationClass = null;
        switch (operationEntry.getKey()) {
            case GET:
                annotationClass = GetMapping.class;
                break;
            case POST:
                annotationClass = PostMapping.class;
                break;
            case PUT:
                annotationClass = PutMapping.class;
                break;
            case PATCH:
                annotationClass = PatchMapping.class;
                break;
            case DELETE:
                annotationClass = DeleteMapping.class;
                break;
        }
        if (annotationClass != null) {
            AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                    .addMember("value", "$S", path);
            getSuccessfulReply(operationEntry.getValue())
                    .<Map.Entry<String, MediaType>>flatMap(APIExtractor::getMediaType)
                    .map(Map.Entry::getKey)
                    .ifPresent(mt -> builder.addMember("produces", "$S", mt));
            Optional.ofNullable(operationEntry.getValue().getRequestBody())
                    .map(RequestBody::getContent)
                    .<Map.Entry<String, MediaType>>flatMap(APIExtractor::getMediaType)
                    .map(Map.Entry::getKey)
                    .filter(s -> !s.isBlank() && !s.equals("application/json"))
                    .ifPresent(mt -> builder.addMember("consumes", "$S", mt));
            return builder.build();

        } else return null;

    }
}
