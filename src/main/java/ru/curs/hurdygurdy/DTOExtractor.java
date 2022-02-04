package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Map;
import java.util.function.BiConsumer;

public abstract class DTOExtractor<T> implements TypeSpecExtractor<T> {

    private final TypeDefiner<T> typeDefiner;

    public DTOExtractor(TypeDefiner<T> typeDefiner) {
        this.typeDefiner = typeDefiner;
    }

    @Override
    public final void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        for (Map.Entry<String, Schema> schemaEntry : openAPI.getComponents().getSchemas().entrySet()) {
            T dto = typeDefiner.getDTO(schemaEntry.getKey(), schemaEntry.getValue(), openAPI);
            typeSpecBiConsumer.accept(ClassCategory.DTO, dto);
        }
    }

}
