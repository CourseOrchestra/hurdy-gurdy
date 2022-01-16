package ru.curs.clickmatters.codegen;

import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Map;
import java.util.function.BiConsumer;

public class DTOExtractor implements TypeSpecExtractor {

    private final TypeDefiner typeDefiner;

    public DTOExtractor(TypeDefiner typeDefiner) {
        this.typeDefiner = typeDefiner;
    }

    @Override
    public void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        for (Map.Entry<String, Schema> schemaEntry : openAPI.getComponents().getSchemas().entrySet()) {
            TypeSpec dto = typeDefiner.getDTO(schemaEntry.getKey(), schemaEntry.getValue());
            typeSpecBiConsumer.accept(ClassCategory.DTO, dto);
        }
    }
}
