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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds {@code nullable} keywords left over in an OpenAPI 3.1 document.
 *
 * <p>OpenAPI 3.1 removed {@code nullable} outright (JSON Schema 2020-12 has no
 * such keyword) in favour of {@code type: [X, "null"]}, so a 3.1 document that
 * still carries it says nothing at all: swagger-parser leaves
 * {@code Schema.getNullable()} null and drops the keyword into the schema's
 * extension map together with every other unrecognised keyword. hurdy-gurdy
 * follows the spec and ignores it — but ignoring it <em>silently</em> is the
 * documented trap of a 3.0 &rarr; 3.1 migration (bump the version string, leave
 * the {@code nullable}s in place, and every nullable field quietly turns
 * non-null), so each occurrence is reported as a warning instead.
 *
 * <p>One instance walks one document; the visited sets it carries make a
 * self-referential schema (or a {@code $ref} reached twice) stop instead of
 * recursing forever.
 */
final class StrayNullableCheck {

    private final Set<Schema<?>> visitedSchemas = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<PathItem> visitedPathItems = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> found = new ArrayList<>();

    private StrayNullableCheck() {
    }

    /**
     * The locations of every stray {@code nullable} in a 3.1 document, as
     * JSON-pointer-like paths (e.g.
     * {@code #/components/schemas/Sample/properties/opt}), in document order.
     * Always empty for a 3.0 document, where {@code nullable} is a legitimate
     * keyword that the parser reads into the schema itself.
     */
    static List<String> locations(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getSpecVersion() != SpecVersion.V31) {
            return List.of();
        }
        StrayNullableCheck check = new StrayNullableCheck();
        check.visitDocument(openAPI);
        return check.found;
    }

    private void visitDocument(OpenAPI openAPI) {
        Components components = openAPI.getComponents();
        if (components != null) {
            visitSchemaMap(components.getSchemas(), "#/components/schemas");
            forEach(components.getParameters(), "#/components/parameters", this::visitParameter);
            forEach(components.getHeaders(), "#/components/headers", this::visitHeader);
            forEach(components.getRequestBodies(), "#/components/requestBodies", this::visitRequestBody);
            forEach(components.getResponses(), "#/components/responses", this::visitResponse);
            forEach(components.getCallbacks(), "#/components/callbacks", this::visitCallback);
            forEach(components.getPathItems(), "#/components/pathItems", this::visitPathItem);
        }
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) -> visitPathItem(pathItem, "#/paths/" + path));
        }
        // 3.1 added webhooks: path items reachable from nowhere else.
        forEach(openAPI.getWebhooks(), "#/webhooks", this::visitPathItem);
    }

    private void visitPathItem(PathItem pathItem, String path) {
        // A callback may point back at a path item already being walked.
        if (pathItem == null || !visitedPathItems.add(pathItem)) {
            return;
        }
        visitParameters(pathItem.getParameters(), path);
        pathItem.readOperationsMap().forEach((method, operation) ->
                visitOperation(operation, path + "/" + method.name().toLowerCase()));
    }

    private void visitOperation(Operation operation, String path) {
        visitParameters(operation.getParameters(), path);
        visitRequestBody(operation.getRequestBody(), path + "/requestBody");
        if (operation.getResponses() != null) {
            operation.getResponses().forEach((code, response) ->
                    visitResponse(response, path + "/responses/" + code));
        }
        forEach(operation.getCallbacks(), path + "/callbacks", this::visitCallback);
    }

    private void visitCallback(Callback callback, String path) {
        if (callback != null) {
            callback.forEach((expression, pathItem) -> visitPathItem(pathItem, path + "/" + expression));
        }
    }

    private void visitRequestBody(RequestBody requestBody, String path) {
        if (requestBody != null) {
            visitContent(requestBody.getContent(), path);
        }
    }

    private void visitResponse(ApiResponse response, String path) {
        if (response != null) {
            visitContent(response.getContent(), path);
            forEach(response.getHeaders(), path + "/headers", this::visitHeader);
        }
    }

    private void visitHeader(Header header, String path) {
        if (header != null) {
            // A header carries either a schema or a content map, never both.
            visitSchema(header.getSchema(), path);
            visitContent(header.getContent(), path);
        }
    }

    private void visitParameters(List<Parameter> parameters, String path) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            visitParameter(parameter, path + "/parameters/" + parameter.getName());
        }
    }

    private void visitParameter(Parameter parameter, String path) {
        if (parameter != null) {
            visitSchema(parameter.getSchema(), path);
            visitContent(parameter.getContent(), path);
        }
    }

    private void visitContent(Content content, String path) {
        if (content == null) {
            return;
        }
        for (Map.Entry<String, MediaType> entry : content.entrySet()) {
            visitSchema(entry.getValue().getSchema(), path + "/content/" + entry.getKey());
        }
    }

    private void visitSchemaMap(Map<String, Schema> schemas, String path) {
        if (schemas != null) {
            schemas.forEach((name, schema) -> visitSchema(schema, path + "/" + name));
        }
    }

    private void visitSchemaList(List<Schema> schemas, String path) {
        if (schemas == null) {
            return;
        }
        for (int i = 0; i < schemas.size(); i++) {
            visitSchema(schemas.get(i), path + "/" + i);
        }
    }

    private void visitSchema(Schema<?> schema, String path) {
        // Identity-based, so a self-referential (or repeatedly $ref'd) schema is
        // visited once instead of recursing forever.
        if (schema == null || !visitedSchemas.add(schema)) {
            return;
        }
        if (schema.getExtensions() != null && schema.getExtensions().containsKey("nullable")) {
            found.add(path);
        }
        visitSchemaMap(schema.getProperties(), path + "/properties");
        visitSchema(schema.getItems(), path + "/items");
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
            visitSchema(additionalProperties, path + "/additionalProperties");
        }
        visitSchemaList(schema.getAllOf(), path + "/allOf");
        visitSchemaList(schema.getAnyOf(), path + "/anyOf");
        visitSchemaList(schema.getOneOf(), path + "/oneOf");
        visitSchema(schema.getNot(), path + "/not");
    }

    /** Applies {@code visitor} to every entry of a components-style map, keyed by name. */
    private static <V> void forEach(Map<String, V> map, String path, NamedVisitor<V> visitor) {
        if (map != null) {
            map.forEach((name, value) -> visitor.visit(value, path + "/" + name));
        }
    }

    @FunctionalInterface
    private interface NamedVisitor<V> {
        void visit(V value, String path);
    }
}
