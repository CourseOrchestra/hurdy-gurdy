package ru.curs.clickmatters.codegen;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;

import javax.lang.model.element.Modifier;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class APIExtractor implements TypeSpecExtractor {
    private final TypeDefiner typeDefiner;
    private final boolean generateResponseParameter;

    public APIExtractor(TypeDefiner typeDefiner, boolean generateResponseParameter) {
        this.typeDefiner = typeDefiner;
        this.generateResponseParameter = generateResponseParameter;
    }

    @Override
    public void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        Paths paths = openAPI.getPaths();
        if (paths == null) return;
        TypeSpec.Builder classBuilder = TypeSpec.interfaceBuilder("Controller");
        for (Map.Entry<String, PathItem> stringPathItemEntry : paths.entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry : stringPathItemEntry.getValue().readOperationsMap().entrySet()) {
                buildMethod(classBuilder, stringPathItemEntry, operationEntry);
            }
        }
        typeSpecBiConsumer.accept(ClassCategory.CONTROLLER, classBuilder.build());
    }

    private void buildMethod(TypeSpec.Builder classBuilder,
                             Map.Entry<String, PathItem> stringPathItemEntry,
                             Map.Entry<PathItem.HttpMethod, Operation> operationEntry) {
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(CaseUtils.snakeToCamel(operationEntry.getValue().getOperationId()))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        methodBuilder.addAnnotation(getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.getKey()));
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnJavaType(operationEntry.getValue(), classBuilder));
        Optional.ofNullable(operationEntry.getValue().getRequestBody()).map(RequestBody::getContent)
                .map(c -> getContentType(c, classBuilder)).ifPresent(typeName ->
                methodBuilder.addParameter(ParameterSpec.builder(
                        typeName,
                        "request")
                        .addAnnotation(org.springframework.web.bind.annotation.RequestBody.class).build())
        );

        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                        safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(), classBuilder)),
                        CaseUtils.snakeToCamel(parameter.getName()))
                        .addAnnotation(
                                AnnotationSpec.builder(PathVariable.class)
                                        .addMember("name", "$S", parameter.getName()).build()
                        )
                        .build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "query".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                        safeBox(typeDefiner.defineJavaType(parameter.getSchema(), classBuilder)),
                        CaseUtils.snakeToCamel(parameter.getName()))
                        .addAnnotation(
                                AnnotationSpec.builder(
                                        RequestParam.class
                                ).addMember("required", "$L", parameter.getRequired())
                                        .addMember("name", "$S", parameter.getName()).build()
                        ).build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "header".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), classBuilder)),
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

    private static Stream<Parameter> getParameterStream(PathItem path, Operation operation) {
        return Stream.concat(
                Optional.ofNullable(path.getParameters()).stream(),
                Optional.ofNullable(operation.getParameters()).stream())
                .flatMap(Collection::stream);
    }

    private TypeName determineReturnJavaType(Operation operation, TypeSpec.Builder parent) {
        return getSuccessfulReply(operation).map(c -> getContentType(c, parent)).orElse(TypeName.VOID);
    }

    private static Optional<Content> getSuccessfulReply(Operation operation) {
        return operation.getResponses().entrySet().stream()
                .filter(r -> r.getKey().matches("2\\d\\d"))
                .map(r -> r.getValue().getContent())
                .filter(Objects::nonNull)
                .findFirst();
    }

    private TypeName getContentType(Content content, TypeSpec.Builder parent) {
        return Optional.ofNullable(content)
                .flatMap(this::getMediaType)
                .map(Map.Entry::getValue)
                .map(MediaType::getSchema)
                .map(s -> typeDefiner.defineJavaType(s, parent))
                .map(APIExtractor::safeUnbox)
                .orElse(TypeName.VOID);
    }

    private Optional<Map.Entry<String, MediaType>> getMediaType(Content content) {
        return content.entrySet().stream().findFirst();
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
            case DELETE:
                annotationClass = DeleteMapping.class;
                break;
        }
        if (annotationClass != null) {
            AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                    .addMember("value", "$S", path);
            getSuccessfulReply(operationEntry.getValue())
                    .flatMap(this::getMediaType)
                    .map(Map.Entry::getKey)
                    .ifPresent(mt -> builder.addMember("produces", "$S", mt));
            return builder.build();
        } else return null;

    }


}
