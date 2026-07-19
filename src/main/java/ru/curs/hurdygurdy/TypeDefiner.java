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

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class TypeDefiner<T> {
    protected static final Pattern CLASS_NAME_PATTERN = Pattern.compile("/([^/$]+)$");
    protected static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([^#]*)#");

    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final GeneratorParams params;
    final Map<String, DTOMeta> externalClasses = new HashMap<>();
    private final Set<String> aliasesBeingInlined = new HashSet<>();
    private Path sourceFile;

    TypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.params = params;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return getEnum(name, schema, openAPI);
        } else if (isArraySchema(schema)) {
            return getArrayAlias(name, schema, openAPI);
        } else {
            return getDTOClass(name, schema, openAPI);
        }
    }

    static boolean isArraySchema(Schema<?> schema) {
        if ("array".equals(schema.getType())) {
            return true;
        }
        Set<String> types = schema.getTypes();
        return types != null && types.size() == 1 && types.contains("array");
    }

    /**
     * The schema behind a same-file reference to an array alias (a named
     * component schema that is a plain {@code type: array}), or null when the
     * reference must stay a class reference: {@code generateAliasAsModel} is
     * set, the reference points into another file (whose schemas are not
     * visible here), or the referenced schema is not an array.
     */
    final Schema<?> inlinableArrayAlias(String ref, OpenAPI openAPI) {
        if (params.isGenerateAliasAsModel() || !extractGroup(ref, FILE_NAME_PATTERN).isBlank()) {
            return null;
        }
        Schema<?> schema = Optional.ofNullable(openAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(extractGroup(ref, CLASS_NAME_PATTERN)))
                .orElse(null);
        return schema != null && isArraySchema(schema) ? schema : null;
    }

    /**
     * Runs {@code action} (the recursive type definition that inlines the alias
     * {@code ref} points to) guarding against alias cycles, which cannot be
     * inlined and would otherwise recurse forever.
     */
    final <R> R inliningAlias(String ref, Supplier<R> action) {
        String name = extractGroup(ref, CLASS_NAME_PATTERN);
        if (!aliasesBeingInlined.add(name)) {
            throw new IllegalStateException(String.format(
                    "Array alias '%s' references itself and cannot be inlined; "
                            + "set generateAliasAsModel to generate a class for it", name));
        }
        try {
            return action.get();
        } finally {
            aliasesBeingInlined.remove(name);
        }
    }

    @SuppressWarnings("unchecked")
    final List<String> getExtendsList(Schema<?> schema) {
        List<String> extendsList = new ArrayList<>();
        Optional.ofNullable(schema.getExtensions()).map(e -> e.get("x-extends"))
                .ifPresent(e -> {
                            if (e instanceof String s) {
                                extendsList.add(s);
                            } else if (e instanceof List) {
                                extendsList.addAll((List<String>) e);
                            }
                        }
                );
        return extendsList;
    }

    final String getEnumName(Schema<?> schema, String typeNameFallback) {
        String simpleName = schema.getTitle();
        if (simpleName == null) {
            simpleName = typeNameFallback;
        }
        if (simpleName == null) {
            throw new IllegalStateException("Inline enum schema must have a title");
        }
        return simpleName;
    }

    final void checkPropertyName(String name, String propertyName) {
        if (params.isForceSnakeCaseForProperties()
                && !propertyName.matches("[$a-z][a-z_0-9]*")) throw new IllegalStateException(
                String.format("Property '%s' of schema '%s' is not in snake case",
                        propertyName, name)
        );
    }


    final Map<String, String> getSubclassMapping(Schema<?> schema) {
        return Optional.ofNullable(schema.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
    }

    abstract T getEnum(String name, Schema<?> schema, OpenAPI openAPI);

    abstract T getDTOClass(String name, Schema<?> schema, OpenAPI openAPI);

    /**
     * The model generated for an array alias when
     * {@link GeneratorParams#isGenerateAliasAsModel()} is set: a class extending
     * {@code ArrayList<Item>} (mirroring openapi-generator's
     * {@code generateAliasAsModel} output), so it serializes as a plain JSON
     * array.
     */
    abstract T getArrayAlias(String name, Schema<?> schema, OpenAPI openAPI);

    com.palantir.javapoet.TypeName defineJavaType(Schema<?> schema,
                                                  OpenAPI openAPI,
                                                  com.palantir.javapoet.TypeSpec.Builder parent,
                                                  String typeNameFallback) {
        throw new IllegalStateException();
    }

    com.squareup.kotlinpoet.TypeName defineKotlinType(Schema<?> schema,
                                                      OpenAPI openAPI,
                                                      com.squareup.kotlinpoet.TypeSpec.Builder parent,
                                                      String typeNameFallback,
                                                      Boolean nullableOverride) {
        throw new IllegalStateException();
    }

    void init(Path currentSourceFile) {
        this.sourceFile = currentSourceFile;
        externalClasses.clear();
        aliasesBeingInlined.clear();
    }


    DTOMeta getReferencedTypeInfo(OpenAPI currentOpenAPI, String ref) {
        String fileName = extractGroup(ref, FILE_NAME_PATTERN);
        String className = extractGroup(ref, CLASS_NAME_PATTERN);
        if (fileName.isBlank()) {
            return new DTOMeta(className,
                    params.getRootPackage(),
                    fileName,
                    getNullable(currentOpenAPI, className, true));
        } else {
            return externalClasses.computeIfAbsent(ref, f -> {
                ParseOptions parseOptions = new ParseOptions();
                Path externalFile = sourceFile.resolveSibling(fileName);
                try {
                    final SwaggerParseResult parseResult = new OpenAPIParser()
                            .readContents(Files.readString(externalFile), null, parseOptions);
                    OpenAPI openAPI = parseResult.getOpenAPI();
                    String packageName = Optional.ofNullable(openAPI.getExtensions())
                            .map(e -> e.get("x-package"))
                            .map(String.class::cast)
                            .orElseThrow(() -> new IllegalStateException(
                                    String.format(
                                            "x-package not defined for externally linked file %s ", externalFile)));
                    return new DTOMeta(className, packageName, fileName, getNullable(openAPI, className, true));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    protected boolean getNullable(OpenAPI currentOpenAPI, String className, Boolean defaultValue) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getNullable)
                .orElse(defaultValue);
    }

    protected String getDefault(OpenAPI currentOpenAPI, String className) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getDefault)
                .map(Object::toString)
                .orElse(null);
    }

    protected boolean isEnum(OpenAPI currentOpenAPI, String className) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getEnum)
                .map(l -> !l.isEmpty())
                .orElse(false);

    }

    protected String extractGroup(String ref, Pattern pattern) {
        Matcher matcher = pattern.matcher(ref);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("Illegal ref:" + ref);
        }
    }
}
