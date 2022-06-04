package ru.curs.hurdygurdy;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

public class JavaCodegen extends Codegen<TypeSpec> {
    public JavaCodegen(GeneratorParams params) {

        super(params, new TypeProducersFactory<>() {
            @Override
            public TypeDefiner<TypeSpec> createTypeDefiner(BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
                return new JavaTypeDefiner(params, typeSpecBiConsumer);
            }

            @Override
            public List<TypeSpecExtractor<TypeSpec>> typeSpecExtractors(TypeDefiner<TypeSpec> typeDefiner) {
                return List.of(new JavaDTOExtractor(typeDefiner),
                        new JavaAPIExtractor(typeDefiner, params));
            }
        });
    }

    @Override
    void writeFile(Path resultDirectory, String packageName, TypeSpec typeSpec) throws IOException {
        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();
        javaFile.writeTo(resultDirectory);
    }
}
