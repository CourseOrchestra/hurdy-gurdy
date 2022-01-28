package ru.curs.hurdygurdy;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

public class JavaCodegen extends Codegen<TypeSpec> {
    public JavaCodegen(String rootPackage, boolean generateResponseParameter) {

        super(rootPackage, new TypeProducersFactory<>() {
            @Override
            public TypeDefiner<TypeSpec> createTypeDefiner(BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
                return new JavaTypeDefiner(rootPackage, typeSpecBiConsumer);
            }

            @Override
            public List<TypeSpecExtractor<TypeSpec>> typeSpecExtractors(TypeDefiner<TypeSpec> typeDefiner) {
                return List.of(new JavaDTOExtractor(typeDefiner),
                        new JavaAPIExtractor(typeDefiner, generateResponseParameter));
            }
        });
    }

    @Override
    void writeFile(Path resultDirectory, String packageName, TypeSpec typeSpec) throws IOException {
        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();
        javaFile.writeTo(resultDirectory);
    }
}
