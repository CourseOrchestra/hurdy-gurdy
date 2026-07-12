package ru.curs.hurdygurdy;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
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
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static ru.curs.hurdygurdy.CaseUtils.normalizeToCamel;

public class JavaAPIExtractor extends APIExtractor<TypeSpec, TypeSpec.Builder> {

    private static final ClassName JAXRS_PATH = ClassName.get("jakarta.ws.rs", "Path");
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
    private static final ClassName MP_REGISTER_REST_CLIENT =
            ClassName.get("org.eclipse.microprofile.rest.client.inject", "RegisterRestClient");
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

    public JavaAPIExtractor(TypeDefiner<TypeSpec> typeDefiner,
                            GeneratorParams params) {
        super(typeDefiner, params,
                (name, role) -> {
                    TypeSpec.Builder b = TypeSpec.interfaceBuilder(normalizeToCamel(name));
                    if (params.getFramework() == Framework.QUARKUS) {
                        b.addAnnotation(AnnotationSpec.builder(JAXRS_PATH)
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
    }

    @Override
    void buildMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
                     Map.Entry<String, PathItem> stringPathItemEntry,
                     Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                     String operationId,
                     Role role,
                     boolean generateResponseParameter) {
        if (getFramework() == Framework.QUARKUS) {
            buildQuarkusMethod(openAPI, classBuilder, stringPathItemEntry,
                    operationEntry, operationId, role, generateResponseParameter);
        } else if (role == Role.CLIENT) {
            buildSpringClientMethod(openAPI, classBuilder, stringPathItemEntry,
                    operationEntry, operationId, generateResponseParameter);
        } else {
            buildSpringMethod(openAPI, classBuilder, stringPathItemEntry,
                    operationEntry, operationId, generateResponseParameter);
        }
    }

    private void buildSpringMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
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
                .flatMap(c -> getContentTypes(c, openAPI, classBuilder, false))
                .forEach(paramSpec ->
                        methodBuilder.addParameter(ParameterSpec.builder(
                                        paramSpec.typeName,
                                        CaseUtils.toIdentifier(paramSpec.name))
                                .addAnnotation(paramSpec.annotation).build())
                );

        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(),
                                        openAPI, classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
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
                                            CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
                                    .addAnnotation(annotationSpec).build());
                        }
                );
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "header".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.getName())))
                        .addAnnotation(
                                AnnotationSpec.builder(
                                                RequestHeader.class
                                        ).addMember("required", "$L", parameter.getRequired())
                                        .addMember("name", "$S", parameter.getName()).build()
                        ).build()));
        if (generateResponseParameter) {
            if (isIncludeRequest(operationEntry.getValue())) {
                methodBuilder.addParameter(ParameterSpec.builder(
                        HttpServletRequest.class,
                        "request").build());
            }
            methodBuilder.addParameter(ParameterSpec.builder(
                    HttpServletResponse.class,
                    "response").build());
        }
        classBuilder.addMethod(methodBuilder.build());
    }

    private void buildQuarkusMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
                     Map.Entry<String, PathItem> stringPathItemEntry,
                     Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                     String operationId,
                     Role role,
                     boolean generateResponseParameter) {
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(operationId)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        getQuarkusMethodAnnotations(operationEntry, stringPathItemEntry.getKey())
                .forEach(methodBuilder::addAnnotation);

        TypeName dtoReturn = determineReturnJavaType(operationEntry.getValue(), openAPI, classBuilder);
        if (generateResponseParameter) {
            methodBuilder.returns(JAXRS_RESPONSE);
            methodBuilder.addJavadoc("@return a $T whose entity is expected to be $L\n",
                    JAXRS_RESPONSE,
                    dtoReturn.equals(TypeName.VOID) ? "empty (no body)" : dtoReturn.toString());
        } else {
            methodBuilder.returns(dtoReturn);
        }

        Optional.ofNullable(operationEntry.getValue().getRequestBody())
                .map(RequestBody::getContent)
                .stream()
                .flatMap(c -> getContentTypes(c, openAPI, classBuilder, true))
                .forEach(paramSpec -> {
                    ParameterSpec.Builder pb = ParameterSpec.builder(
                            paramSpec.typeName, CaseUtils.toIdentifier(paramSpec.name));
                    if (paramSpec.annotation != null) {
                        pb.addAnnotation(paramSpec.annotation);
                    }
                    methodBuilder.addParameter(pb.build());
                });

        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(),
                                        openAPI, classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
                        .addAnnotation(AnnotationSpec.builder(JAXRS_PATH_PARAM)
                                .addMember("value", "$S", parameter.getName()).build())
                        .build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "query".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> {
                    ParameterSpec.Builder pb = ParameterSpec.builder(
                                    safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI,
                                            classBuilder, null)),
                                    CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
                            .addAnnotation(AnnotationSpec.builder(JAXRS_QUERY_PARAM)
                                    .addMember("value", "$S", parameter.getName()).build());
                    Optional.ofNullable(parameter.getSchema())
                            .map(Schema::getDefault)
                            .ifPresent(d -> pb.addAnnotation(AnnotationSpec.builder(JAXRS_DEFAULT_VALUE)
                                    .addMember("value", "$S", d.toString()).build()));
                    methodBuilder.addParameter(pb.build());
                });
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "header".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI, classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.getName())))
                        .addAnnotation(AnnotationSpec.builder(JAXRS_HEADER_PARAM)
                                .addMember("value", "$S", parameter.getName()).build())
                        .build()));
        if (generateResponseParameter && isIncludeRequest(operationEntry.getValue())
                && role == Role.CONTROLLER) {
            methodBuilder.addParameter(ParameterSpec.builder(
                            JAXRS_REQUEST_CONTEXT, "requestContext")
                    .addAnnotation(JAXRS_CONTEXT).build());
        }
        classBuilder.addMethod(methodBuilder.build());
    }

    private void buildSpringClientMethod(OpenAPI openAPI, TypeSpec.Builder classBuilder,
                     Map.Entry<String, PathItem> stringPathItemEntry,
                     Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                     String operationId,
                     boolean generateResponseParameter) {
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder(operationId)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        AnnotationSpec exchange =
                getSpringExchangeAnnotationSpec(operationEntry, stringPathItemEntry.getKey());
        if (exchange != null) {
            methodBuilder.addAnnotation(exchange);
        }
        TypeName dtoReturn = determineReturnJavaType(operationEntry.getValue(), openAPI, classBuilder);
        if (generateResponseParameter) {
            TypeName body = dtoReturn.equals(TypeName.VOID) ? ClassName.get(Void.class) : dtoReturn.box();
            methodBuilder.returns(ParameterizedTypeName.get(SPRING_RESPONSE_ENTITY, body));
        } else {
            methodBuilder.returns(dtoReturn);
        }
        Optional.ofNullable(operationEntry.getValue().getRequestBody())
                .map(RequestBody::getContent)
                .stream()
                .flatMap(c -> getContentTypes(c, openAPI, classBuilder, false))
                .forEach(paramSpec ->
                        methodBuilder.addParameter(ParameterSpec.builder(
                                        paramSpec.typeName, CaseUtils.toIdentifier(paramSpec.name))
                                .addAnnotation(paramSpec.annotation).build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "path".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeUnbox(typeDefiner.defineJavaType(parameter.getSchema(),
                                        openAPI, classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
                        .addAnnotation(AnnotationSpec.builder(PathVariable.class)
                                .addMember("name", "$S", parameter.getName()).build())
                        .build()));
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "query".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> {
                    AnnotationSpec.Builder builder = AnnotationSpec.builder(RequestParam.class)
                            .addMember("required", "$L", parameter.getRequired())
                            .addMember("name", "$S", parameter.getName());
                    Optional.ofNullable(parameter.getSchema())
                            .map(Schema::getDefault)
                            .ifPresent(d -> builder.addMember("defaultValue", "$S", d.toString()));
                    methodBuilder.addParameter(ParameterSpec.builder(
                                    safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI,
                                            classBuilder, null)),
                                    CaseUtils.toIdentifier(CaseUtils.snakeToCamel(parameter.getName())))
                            .addAnnotation(builder.build()).build());
                });
        getParameterStream(stringPathItemEntry.getValue(), operationEntry.getValue())
                .filter(parameter -> "header".equalsIgnoreCase(parameter.getIn()))
                .forEach(parameter -> methodBuilder.addParameter(ParameterSpec.builder(
                                safeBox(typeDefiner.defineJavaType(parameter.getSchema(), openAPI,
                                        classBuilder, null)),
                                CaseUtils.toIdentifier(CaseUtils.kebabToCamel(parameter.getName())))
                        .addAnnotation(AnnotationSpec.builder(RequestHeader.class)
                                .addMember("required", "$L", parameter.getRequired())
                                .addMember("name", "$S", parameter.getName()).build())
                        .build()));
        classBuilder.addMethod(methodBuilder.build());
    }

    private AnnotationSpec getSpringExchangeAnnotationSpec(
            Map.Entry<PathItem.HttpMethod, Operation> operationEntry, String path) {
        ClassName annotationClass = switch (operationEntry.getKey()) {
            case GET -> SPRING_GET_EXCHANGE;
            case POST -> SPRING_POST_EXCHANGE;
            case PUT -> SPRING_PUT_EXCHANGE;
            case PATCH -> SPRING_PATCH_EXCHANGE;
            case DELETE -> SPRING_DELETE_EXCHANGE;
            default -> null;
        };
        if (annotationClass == null) {
            return null;
        }
        AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                .addMember("value", "$S", path);
        getSuccessfulReply(operationEntry.getValue())
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .ifPresent(mt -> builder.addMember("accept", "$S", mt));
        Optional.ofNullable(operationEntry.getValue().getRequestBody())
                .map(RequestBody::getContent)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .filter(s -> !s.isBlank() && !s.equals("application/json"))
                .ifPresent(mt -> builder.addMember("contentType", "$S", mt));
        return builder.build();
    }

    private static boolean isIncludeRequest(Operation operation) {
        return Optional.ofNullable(operation.getExtensions())
                .map(m -> m.get("x-include-request"))
                .map(v -> {
                    if (v instanceof Boolean b) return b;
                    if (v instanceof String s) return Boolean.parseBoolean(s);
                    return false;
                }).orElse(false);
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
                .flatMap(c -> getContentTypes(c, openAPI, parent, false))
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

    private Stream<RequestPartParams> getContentTypes(Content content, OpenAPI openAPI,
                                                      TypeSpec.Builder parent, boolean quarkus) {
        final Optional<Map.Entry<String, MediaType>> mediaTypeEntry =
                Optional.ofNullable(content)
                        .flatMap(APIExtractor::getMediaType);
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
                                quarkus
                                        ? AnnotationSpec.builder(QUARKUS_REST_FORM)
                                                .addMember("value", "$S", e.getKey()).build()
                                        : AnnotationSpec.builder(RequestPart.class)
                                                .addMember("name", "$S", e.getKey()).build()));
            } else {
                //Single-part
                return Optional.ofNullable(entry.getValue().getSchema()).stream()
                        .map(s -> typeDefiner.defineJavaType(s, openAPI, parent, null))
                        .map(JavaAPIExtractor::safeUnbox).map(t ->
                                new RequestPartParams(t,
                                        "request",
                                        quarkus
                                                ? null
                                                : AnnotationSpec.builder(
                                                        org.springframework.web.bind.annotation.RequestBody.class)
                                                        .build()));
            }
        }
    }

    private List<AnnotationSpec> getQuarkusMethodAnnotations(
            Map.Entry<PathItem.HttpMethod, Operation> operationEntry, String path) {
        List<AnnotationSpec> result = new ArrayList<>();
        ClassName verb = switch (operationEntry.getKey()) {
            case GET -> JAXRS_GET;
            case POST -> JAXRS_POST;
            case PUT -> JAXRS_PUT;
            case PATCH -> JAXRS_PATCH;
            case DELETE -> JAXRS_DELETE;
            default -> null;
        };
        if (verb == null) {
            return result;
        }
        result.add(AnnotationSpec.builder(verb).build());
        result.add(AnnotationSpec.builder(JAXRS_PATH).addMember("value", "$S", path).build());
        getSuccessfulReply(operationEntry.getValue())
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .ifPresent(mt -> result.add(AnnotationSpec.builder(JAXRS_PRODUCES)
                        .addMember("value", "$S", mt).build()));
        Optional.ofNullable(operationEntry.getValue().getRequestBody())
                .map(RequestBody::getContent)
                .flatMap(APIExtractor::getMediaType)
                .map(Map.Entry::getKey)
                .filter(s -> !s.isBlank())
                .ifPresent(mt -> result.add(AnnotationSpec.builder(JAXRS_CONSUMES)
                        .addMember("value", "$S", mt).build()));
        return result;
    }

    private AnnotationSpec getControllerMethodAnnotationSpec(Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                                                             String path) {
        Class<?> annotationClass = switch (operationEntry.getKey()) {
            case GET -> GetMapping.class;
            case POST -> PostMapping.class;
            case PUT -> PutMapping.class;
            case PATCH -> PatchMapping.class;
            case DELETE -> DeleteMapping.class;
            default -> null;
        };
        if (annotationClass != null) {
            AnnotationSpec.Builder builder = AnnotationSpec.builder(annotationClass)
                    .addMember("value", "$S", path);
            getSuccessfulReply(operationEntry.getValue())
                    .flatMap(APIExtractor::getMediaType)
                    .map(Map.Entry::getKey)
                    .ifPresent(mt -> builder.addMember("produces", "$S", mt));
            Optional.ofNullable(operationEntry.getValue().getRequestBody())
                    .map(RequestBody::getContent)
                    .flatMap(APIExtractor::getMediaType)
                    .map(Map.Entry::getKey)
                    .filter(s -> !s.isBlank() && !s.equals("application/json"))
                    .ifPresent(mt -> builder.addMember("consumes", "$S", mt));
            return builder.build();

        } else return null;

    }
}
