package ru.curs.clickmatters.codegen;

import com.squareup.kotlinpoet.TypeSpec;

public class KotlinDTOExtractor extends DTOExtractor<TypeSpec> {
    public KotlinDTOExtractor(TypeDefiner<TypeSpec> typeDefiner) {
        super(typeDefiner);
    }
}
