package ru.curs.clickmatters.codegen;

import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Map;
import java.util.function.BiConsumer;

public class JavaDTOExtractor extends DTOExtractor<TypeSpec> {
    public JavaDTOExtractor(TypeDefiner<TypeSpec> typeDefiner) {
        super(typeDefiner);
    }
}
