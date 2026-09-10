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
 */
final class StrayNullableCheck {

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
        List<String> found = new ArrayList<>();
        if (openAPI == null || openAPI.getSpecVersion() != SpecVersion.V31) {
            return found;
        }
        Set<Schema<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Components components = openAPI.getComponents();
        if (components != null) {
            visitSchemaMap(components.getSchemas(), "#/components/schemas", visited, found);
            if (components.getParameters() != null) {
                components.getParameters().forEach((name, parameter) -> visitParameter(
                        parameter, "#/components/parameters/" + name, visited, found));
            }
            if (components.getRequestBodies() != null) {
                components.getRequestBodies().forEach((name, body) -> visitContent(
                        body.getContent(), "#/components/requestBodies/" + name, visited, found));
            }
            if (components.getResponses() != null) {
                components.getResponses().forEach((name, response) -> visitContent(
                        response.getContent(), "#/components/responses/" + name, visited, found));
            }
        }
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) ->
                    visitPathItem(pathItem, "#/paths/" + path, visited, found));
        }
        return found;
    }

    private static void visitPathItem(PathItem pathItem, String path,
                                      Set<Schema<?>> visited, List<String> found) {
        if (pathItem == null) {
            return;
        }
        visitParameters(pathItem.getParameters(), path, visited, found);
        pathItem.readOperationsMap().forEach((method, operation) ->
                visitOperation(operation, path + "/" + method.name().toLowerCase(), visited, found));
    }

    private static void visitOperation(Operation operation, String path,
                                       Set<Schema<?>> visited, List<String> found) {
        visitParameters(operation.getParameters(), path, visited, found);
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null) {
            visitContent(requestBody.getContent(), path + "/requestBody", visited, found);
        }
        if (operation.getResponses() != null) {
            operation.getResponses().forEach((code, response) ->
                    visitResponse(response, path + "/responses/" + code, visited, found));
        }
    }

    private static void visitResponse(ApiResponse response, String path,
                                      Set<Schema<?>> visited, List<String> found) {
        if (response != null) {
            visitContent(response.getContent(), path, visited, found);
        }
    }

    private static void visitParameters(List<Parameter> parameters, String path,
                                        Set<Schema<?>> visited, List<String> found) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            visitParameter(parameter, path + "/parameters/" + parameter.getName(), visited, found);
        }
    }

    private static void visitParameter(Parameter parameter, String path,
                                       Set<Schema<?>> visited, List<String> found) {
        if (parameter == null) {
            return;
        }
        visitSchema(parameter.getSchema(), path, visited, found);
        visitContent(parameter.getContent(), path, visited, found);
    }

    private static void visitContent(Content content, String path,
                                     Set<Schema<?>> visited, List<String> found) {
        if (content == null) {
            return;
        }
        for (Map.Entry<String, MediaType> entry : content.entrySet()) {
            visitSchema(entry.getValue().getSchema(),
                    path + "/content/" + entry.getKey(), visited, found);
        }
    }

    private static void visitSchemaMap(Map<String, Schema> schemas, String path,
                                       Set<Schema<?>> visited, List<String> found) {
        if (schemas == null) {
            return;
        }
        schemas.forEach((name, schema) -> visitSchema(schema, path + "/" + name, visited, found));
    }

    private static void visitSchemaList(List<Schema> schemas, String path,
                                        Set<Schema<?>> visited, List<String> found) {
        if (schemas == null) {
            return;
        }
        for (int i = 0; i < schemas.size(); i++) {
            visitSchema(schemas.get(i), path + "/" + i, visited, found);
        }
    }

    private static void visitSchema(Schema<?> schema, String path,
                                    Set<Schema<?>> visited, List<String> found) {
        // Identity-based, so a self-referential (or repeatedly $ref'd) schema is
        // visited once instead of recursing forever.
        if (schema == null || !visited.add(schema)) {
            return;
        }
        if (schema.getExtensions() != null && schema.getExtensions().containsKey("nullable")) {
            found.add(path);
        }
        visitSchemaMap(schema.getProperties(), path + "/properties", visited, found);
        visitSchema(schema.getItems(), path + "/items", visited, found);
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
            visitSchema(additionalProperties, path + "/additionalProperties", visited, found);
        }
        visitSchemaList(schema.getAllOf(), path + "/allOf", visited, found);
        visitSchemaList(schema.getAnyOf(), path + "/anyOf", visited, found);
        visitSchemaList(schema.getOneOf(), path + "/oneOf", visited, found);
        visitSchema(schema.getNot(), path + "/not", visited, found);
    }
}
