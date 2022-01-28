package ru.curs.clickmatters.hurdygurdy;

import io.swagger.v3.oas.models.media.Schema;

import java.util.function.BiConsumer;

public abstract class TypeDefiner<T> {
    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final String rootPackage;

    TypeDefiner(String rootPackage, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.rootPackage = rootPackage;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    final T getDTO(String name, Schema<?> schema) {
        if (schema.getEnum() != null)
            return getEnum(name, schema);
        else
            return getDTOClass(name, schema);
    }

    abstract T getEnum(String name, Schema<?> schema);

    abstract T getDTOClass(String name, Schema<?> schema);

    com.squareup.javapoet.TypeName defineJavaType(Schema<?> schema,
                                                  com.squareup.javapoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }

    com.squareup.kotlinpoet.TypeName defineKotlinType(Schema<?> schema,
                                                      com.squareup.kotlinpoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }
}
