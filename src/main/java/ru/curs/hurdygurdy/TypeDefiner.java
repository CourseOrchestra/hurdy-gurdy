package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public abstract class TypeDefiner<T> {
    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final String rootPackage;

    TypeDefiner(String rootPackage, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.rootPackage = rootPackage;
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

    final Map<String, String> getSubclassMapping(Schema<?> schema) {
        return Optional.ofNullable(schema.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
    }

    abstract T getEnum(String name, Schema<?> schema, OpenAPI openAPI);

    abstract T getDTOClass(String name, Schema<?> schema, OpenAPI openAPI);

    com.squareup.javapoet.TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI,
                                                  com.squareup.javapoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }

    com.squareup.kotlinpoet.TypeName defineKotlinType(Schema<?> schema, OpenAPI openAPI,
                                                      com.squareup.kotlinpoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }
}
