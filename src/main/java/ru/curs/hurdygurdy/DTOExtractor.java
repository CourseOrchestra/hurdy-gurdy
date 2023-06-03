package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public abstract class DTOExtractor<T> implements TypeSpecExtractor<T> {

    private final TypeDefiner<T> typeDefiner;

    public DTOExtractor(TypeDefiner<T> typeDefiner) {
        this.typeDefiner = typeDefiner;
    }

    @Override
    public final void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        Map<String, Schema> stringSchemaMap =
                Optional.ofNullable(openAPI).map(OpenAPI::getComponents)
                        .map(Components::getSchemas).orElse(Collections.emptyMap());
        for (Map.Entry<String, Schema> schemaEntry : stringSchemaMap.entrySet()) {
            T dto = typeDefiner.getDTO(schemaEntry.getKey(), schemaEntry.getValue(), openAPI);
            typeSpecBiConsumer.accept(ClassCategory.DTO, dto);
        }
    }

}
