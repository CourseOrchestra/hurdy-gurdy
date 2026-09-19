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

package ru.curs.hurdygurdy.model;

import ru.curs.hurdygurdy.CaseUtils;
import ru.curs.hurdygurdy.Role;
import ru.curs.hurdygurdy.emit.TypeDefiner;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the paths of a document into the language-neutral model the generators
 * emit from.
 *
 * <p>The only place on the API path that touches {@code io.swagger.*}. Every
 * question it answers — is this parameter required, does it have a default, is
 * the body one value or several parts, is that part always present — is a
 * question about the specification with one right answer. Asking it once here,
 * rather than once per language, is what makes a Java/Kotlin divergence
 * unrepresentable rather than merely unlikely.
 *
 * <p>What is deliberately <em>not</em> resolved here is the type behind a
 * schema. {@code byte[]} against {@code ByteArray}, {@code Integer} against
 * {@code Int}, a nullable Kotlin type against a boxed Java one — that mapping is
 * the one genuinely per-language step, and it stays with the type definers.
 */
public final class ApiModelBuilder {

    private final TypeDefiner<?> typeDefiner;

    /**
     * Creates a builder that reads documents with the given definer's help.
     *
     * @param typeDefiner consulted for the questions whose answer lives in the
     *                    document a {@code $ref} points into rather than the one
     *                    being generated
     */
    public ApiModelBuilder(TypeDefiner<?> typeDefiner) {
        this.typeDefiner = typeDefiner;
    }

    /**
     * The interfaces to generate for one role, each with its operations in
     * document order.
     *
     * @param openAPI the document to read
     * @param role    the kind of interface being generated, which supplies the
     *                name suffix
     * @return the interfaces, in the order their first operation was met
     */
    public List<InterfaceModel> build(OpenAPI openAPI, Role role) {
        if (openAPI.getPaths() == null) {
            return List.of();
        }
        Map<String, List<OperationModel>> byInterface = new LinkedHashMap<>();
        openAPI.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    List<String> tags = operation.getTags();
                    String typeName = CaseUtils.snakeToCamel(
                            tags != null && !tags.isEmpty() ? tags.get(0) : "", true) + role.getSuffix();
                    byInterface.computeIfAbsent(typeName, k -> new ArrayList<>())
                            .add(operation(openAPI, pathItem, operation, path, httpMethod));
                }));
        return byInterface.entrySet().stream()
                .map(e -> new InterfaceModel(e.getKey(), role, List.copyOf(e.getValue())))
                .toList();
    }

    private OperationModel operation(OpenAPI openAPI, PathItem pathItem, Operation operation,
                                     String path, PathItem.HttpMethod httpMethod) {
        String operationId = CaseUtils.snakeToCamel(operation.getOperationId());
        if (operationId == null) {
            operationId = CaseUtils.pathToCamel(path)
                    + CaseUtils.snakeToCamel(httpMethod.name().toLowerCase(Locale.ROOT), true);
        }
        return new OperationModel(operationId, httpMethod, path,
                successfulReply(operation), requestBody(operation),
                parameters(openAPI, pathItem, operation), isIncludeRequest(operation));
    }

    /**
     * The body of the first 2xx reply that declares content. A reply's body is
     * what the endpoint promises to send back, so it counts as always present.
     */
    private BodyModel successfulReply(Operation operation) {
        return operation.getResponses().entrySet().stream()
                .filter(r -> r.getKey().matches("2\\d\\d"))
                .map(r -> r.getValue().getContent())
                .filter(Objects::nonNull)
                .findFirst()
                .map(content -> body(content, true))
                .orElse(null);
    }

    /**
     * The request body, or null when the operation takes none.
     *
     * <p>A body counts as present only when the operation declares
     * {@code required: true}: OpenAPI defaults {@code requestBody.required} to
     * false, and a body that may be left out of the call is exactly an optional
     * argument.
     */
    private BodyModel requestBody(Operation operation) {
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null) {
            return null;
        }
        return body(body.getContent(), Boolean.TRUE.equals(body.getRequired()));
    }

    private BodyModel body(Content content, boolean required) {
        Optional<Map.Entry<String, MediaType>> entry = mediaType(content);
        if (entry.isEmpty()) {
            return null;
        }
        String type = entry.get().getKey();
        Schema<?> schema = entry.get().getValue().getSchema();
        if (!"multipart/form-data".equalsIgnoreCase(type)) {
            return new BodyModel(type, required, false, schema, List.of());
        }
        // A part is present only if the body itself is required AND the multipart
        // object lists that part in its own `required`.
        Set<String> requiredParts = schema == null || schema.getRequired() == null
                ? Set.of()
                : Set.copyOf(schema.getRequired());
        Map<String, Schema> properties = schema == null ? null : schema.getProperties();
        List<PartModel> parts = properties == null
                ? List.of()
                : properties.entrySet().stream()
                        .map(p -> new PartModel(p.getKey(), p.getValue(),
                                required && requiredParts.contains(p.getKey())))
                        .toList();
        return new BodyModel(type, required, true, null, parts);
    }

    /**
     * The operation's parameters, grouped by position: path, then query, then
     * header, which is the order the generated signature declares them in.
     */
    private List<ParameterModel> parameters(OpenAPI openAPI, PathItem pathItem, Operation operation) {
        List<Parameter> declared = parameterStream(pathItem, operation).toList();
        List<ParameterModel> result = new ArrayList<>();
        for (ParameterModel.In in : ParameterModel.In.values()) {
            for (Parameter parameter : declared) {
                if (in.name().equalsIgnoreCase(parameter.getIn())) {
                    result.add(parameter(openAPI, parameter, in));
                }
            }
        }
        return List.copyOf(result);
    }

    private ParameterModel parameter(OpenAPI openAPI, Parameter parameter, ParameterModel.In in) {
        // A header name is kebab-case by convention (X-Trace-Id); the others are
        // snake_case.
        String identifier = CaseUtils.toIdentifier(in == ParameterModel.In.HEADER
                ? CaseUtils.kebabToCamel(parameter.getName())
                : CaseUtils.snakeToCamel(parameter.getName()));
        boolean required = Boolean.TRUE.equals(parameter.getRequired());
        String defaultValue = typeDefiner.effectiveDefault(parameter.getSchema(), openAPI);
        // Present when it cannot be absent: a path variable is part of the URL, a
        // required parameter is demanded of the caller, and one with a default has
        // that default substituted by the framework — which is only true because
        // the same call decides what goes into the annotation.
        boolean present = in == ParameterModel.In.PATH || required || defaultValue != null;
        return new ParameterModel(parameter.getName(), identifier, in, parameter.getSchema(),
                required, defaultValue, present);
    }

    /**
     * Whether the operation asked, with {@code x-include-request}, to be handed
     * the raw request object in addition to its declared parameters.
     */
    private static boolean isIncludeRequest(Operation operation) {
        return Optional.ofNullable(operation.getExtensions())
                .map(m -> m.get("x-include-request"))
                .map(v -> {
                    if (v instanceof Boolean b) return b;
                    if (v instanceof String s) return Boolean.parseBoolean(s);
                    return false;
                }).orElse(false);
    }

    /** The first media type of a content map, which is the one generated for. */
    static Optional<Map.Entry<String, MediaType>> mediaType(Content content) {
        return Optional.ofNullable(content).flatMap(c -> c.entrySet().stream().findFirst());
    }

    /**
     * The operation's parameters together with the ones its path declares for
     * every operation on it.
     */
    private static Stream<Parameter> parameterStream(PathItem path, Operation operation) {
        return Stream.concat(
                Optional.ofNullable(path.getParameters()).stream(),
                Optional.ofNullable(operation.getParameters()).stream())
                .flatMap(Collection::stream)
                //Parameters with the same name defined in operation have priority
                .collect(Collectors.toMap(
                        Parameter::getName,
                        p -> p,
                        (a, b) -> b,
                        //We must respect the order of declaration
                        LinkedHashMap::new))
                .values().stream();
    }
}
