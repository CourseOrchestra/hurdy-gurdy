package ru.curs.hurdygurdy;

import com.squareup.kotlinpoet.FileSpec;
import com.squareup.kotlinpoet.TypeSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

public class KotlinCodegen extends Codegen<TypeSpec> {
    public KotlinCodegen(GeneratorParams params) {
        super(params, new TypeProducersFactory<>() {
            @Override
            public TypeDefiner<TypeSpec> createTypeDefiner(BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
                return new KotlinTypeDefiner(params, typeSpecBiConsumer);
            }

            @Override
            public List<TypeSpecExtractor<TypeSpec>> typeSpecExtractors(TypeDefiner<TypeSpec> typeDefiner) {
                return List.of(new KotlinDTOExtractor(typeDefiner),
                        new KotlinAPIExtractor(typeDefiner, params));
            }
        });
    }

    @Override
    void writeFile(Path resultDirectory, String packageName, TypeSpec typeSpec) throws IOException {
        var ktFile = FileSpec.get(packageName, typeSpec);
        ktFile.writeTo(resultDirectory);
    }
}
