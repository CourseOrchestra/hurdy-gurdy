package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;

import java.util.function.BiConsumer;

public interface TypeSpecExtractor<T> {
    void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer);
}
