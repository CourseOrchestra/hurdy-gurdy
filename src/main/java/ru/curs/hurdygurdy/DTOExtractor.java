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
            // An array alias has no class of its own unless generateAliasAsModel
            // is set: it is inlined (List<...>) at every point of use instead.
            if (TypeDefiner.isArraySchema(schemaEntry.getValue())
                    && !typeDefiner.params.isGenerateAliasAsModel()) {
                continue;
            }
            T dto = typeDefiner.getDTO(schemaEntry.getKey(), schemaEntry.getValue(), openAPI);
            typeSpecBiConsumer.accept(ClassCategory.DTO, dto);
        }
    }

}
