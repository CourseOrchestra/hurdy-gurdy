package ru.curs.hurdygurdy;

import com.palantir.javapoet.TypeSpec;

public class JavaDTOExtractor extends DTOExtractor<TypeSpec> {
    public JavaDTOExtractor(TypeDefiner<TypeSpec> typeDefiner) {
        super(typeDefiner);
    }
}
