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

import ru.curs.hurdygurdy.extract.DTOExtractor;
import com.squareup.kotlinpoet.FileSpec;
import com.squareup.kotlinpoet.TypeSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Generates Kotlin sources.
 *
 * <p>The two Kotlin classes it drives are named in full rather than imported.
 * javadoc reads only the Java sources and never sees a Kotlin declaration, and
 * an unresolvable {@code import} is a hard error where an unresolvable name
 * inside a method body is not — so importing them would break the javadoc build
 * that the release relies on.
 */
public class KotlinCodegen extends Codegen<TypeSpec> {
    /**
     * Creates a Kotlin generator.
     *
     * @param params what to generate and how
     */
    public KotlinCodegen(GeneratorParams params) {
        super(params, new TypeProducersFactory<TypeSpec, ru.curs.hurdygurdy.emit.KotlinTypeDefiner>() {
            @Override
            public ru.curs.hurdygurdy.emit.KotlinTypeDefiner createTypeDefiner(
                    BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
                return new ru.curs.hurdygurdy.emit.KotlinTypeDefiner(params, typeSpecBiConsumer);
            }

            @Override
            public List<TypeSpecExtractor<TypeSpec>> typeSpecExtractors(
                    ru.curs.hurdygurdy.emit.KotlinTypeDefiner typeDefiner) {
                return List.of(new DTOExtractor<>(typeDefiner, params),
                        new ru.curs.hurdygurdy.extract.KotlinAPIExtractor(typeDefiner, params));
            }
        });
    }

    @Override
    String typeName(TypeSpec typeSpec) {
        return typeSpec.getName();
    }

    @Override
    void writeFile(Path resultDirectory, String packageName, TypeSpec typeSpec) throws IOException {
        var ktFile = FileSpec.get(packageName, typeSpec);
        ktFile.writeTo(resultDirectory);
    }
}
