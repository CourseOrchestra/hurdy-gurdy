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
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import javax.lang.model.element.Modifier;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;

public class JavaAPIExtractor extends APIExtractor<TypeSpec, TypeSpec.Builder> {

    public JavaAPIExtractor(TypeDefiner<TypeSpec> typeDefiner,
                            boolean generateResponseParameter,
                            boolean generateApiInterface) {
        super(typeDefiner, generateResponseParameter, generateApiInterface,
                TypeSpec::interfaceBuilder,
                TypeSpec.Builder::build);
    }


    @Override
    void buildMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
                     Map.Entry<String, PathItem> stringPathItemEntry,
                     Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                     boolean generateResponseParameter) {
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(CaseUtils.snakeToCamel(operationEntry.getValue().getOperationId()))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        methodBuilder.addAnnotation(getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.getKey()));
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnJavaType(operationEntry.getValue(), openAPI, classBuilder));
        Optional.ofNullable(operationEntry.getValue().getRequestBody()).map(RequestBody::getContent)
                .map(c -> getContentType(c, openAPI, classBuilder)).ifPresent(typeName ->
                        methodBuilder.addParameter(ParameterSpec.builder(
                                        typeName,
                                        "request")
                                .addAnnotation(org.springframework.web.bind.annotation.RequestBody.class).build())
                );

        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder)),
                                CaseUtils.snakeToCamel(parameter.getName()))
                        .addAnnotation(
                                AnnotationSpec.builder(PathVariable.class)
                                        .addMember("name", "$S", parameter.getName()).build()
                        )
                        .build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "query".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder)),
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
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder)),
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
        return getSuccessfulReply(operation).map(c -> getContentType(c, openAPI, parent)).orElse(TypeName.VOID);
    }

    private TypeName getContentType(Content content, OpenAPI openAPI, TypeSpec.Builder parent) {
        return Optional.ofNullable(content)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getValue)
                .map(MediaType::getSchema)
                .map(s -> typeDefiner.defineJavaType(s, openAPI, parent))
                .map(JavaAPIExtractor::safeUnbox)
                .orElse(TypeName.VOID);
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
                    .flatMap(APIExtractor::getMediaType)
                    .map(Map.Entry::getKey)
                    .ifPresent(mt -> builder.addMember("produces", "$S", mt));
            return builder.build();
        } else return null;

    }
}
