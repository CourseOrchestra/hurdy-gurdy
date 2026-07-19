/*
 * Copyright 2026 Ivan Ponomarev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
