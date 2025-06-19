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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class TypeDefiner<T> {
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("/([^/$]+)$");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([^#]*)#");

    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final GeneratorParams params;
    final Map<String, DTOMeta> externalClasses = new HashMap<>();
    private Path sourceFile;

    TypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.params = params;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return getEnum(name, schema, openAPI);
        } else {
            return getDTOClass(name, schema, openAPI);
        }
    }

    @SuppressWarnings("unchecked")
    final List<String> getExtendsList(Schema<?> schema) {
        List<String> extendsList = new ArrayList<>();
        Optional.ofNullable(schema.getExtensions()).map(e -> e.get("x-extends"))
                .ifPresent(e -> {
                            if (e instanceof String) {
                                extendsList.add((String) e);
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

    com.squareup.javapoet.TypeName defineJavaType(Schema<?> schema,
                                                  OpenAPI openAPI,
                                                  com.squareup.javapoet.TypeSpec.Builder parent,
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
    }


    DTOMeta getReferencedTypeInfo(OpenAPI currentOpenAPI, String ref) {
        String fileName = extractGroup(ref, FILE_NAME_PATTERN);
        String className = extractGroup(ref, CLASS_NAME_PATTERN);
        if (fileName.isBlank()) {
            return new DTOMeta(className,
                    params.getRootPackage(),
                    fileName,
                    getNullable(currentOpenAPI, className));
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
                    return new DTOMeta(className, packageName, fileName, getNullable(openAPI, className));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private boolean getNullable(OpenAPI currentOpenAPI, String className) {
        return Optional.ofNullable(currentOpenAPI.getComponents())
                .map(Components::getSchemas)
                .map(map -> map.get(className))
                .map(Schema::getNullable)
                .orElse(true);
    }

    private String extractGroup(String ref, Pattern pattern) {
        Matcher matcher = pattern.matcher(ref);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("Illegal ref:" + ref);
        }
    }
}
