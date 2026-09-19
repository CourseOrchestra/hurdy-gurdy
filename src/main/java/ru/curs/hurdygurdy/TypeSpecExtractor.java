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

import io.swagger.v3.oas.models.OpenAPI;

import java.util.function.BiConsumer;

/**
 * Produces generated types from a parsed document.
 *
 * @param <T> the generated type: a JavaPoet or KotlinPoet {@code TypeSpec}
 */
public interface TypeSpecExtractor<T> {
    /**
     * Generates every type this extractor is responsible for.
     *
     * @param openAPI             the document to read
     * @param typeSpecBiConsumer  receives each generated type with the category
     *                            that decides its subpackage
     */
    void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer);
}
