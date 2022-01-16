package ru.curs.clickmatters.codegen;

import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;

import java.util.function.BiConsumer;

public interface TypeSpecExtractor {
    void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer);
}
