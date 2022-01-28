package ru.curs.hurdygurdy;

import com.squareup.javapoet.TypeSpec;

public class JavaDTOExtractor extends DTOExtractor<TypeSpec> {
    public JavaDTOExtractor(TypeDefiner<TypeSpec> typeDefiner) {
        super(typeDefiner);
    }
}
