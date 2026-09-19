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

import ru.curs.hurdygurdy.emit.TypeDefiner;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Supplies the two language-specific halves of a generation run: the type
 * definer, and the extractors that drive it.
 *
 * <p>{@code D} is the <em>concrete</em> definer type, so an extractor receives
 * the definer it actually needs rather than the language-neutral base — which is
 * what lets {@link TypeDefiner} stay free of JavaPoet and KotlinPoet.
 *
 * @param <T> the generated type
 * @param <D> the concrete type definer producing it
 */
public interface TypeProducersFactory<T, D extends TypeDefiner<T>> {
    /**
     * Creates the type definer for this run.
     *
     * @param typeSpecBiConsumer where a definer emits types it generates as a
     *                           side effect of resolving one
     * @return the definer
     */
    D createTypeDefiner(BiConsumer<ClassCategory, T> typeSpecBiConsumer);

    /**
     * The extractors to run, in order.
     *
     * @param typeDefiner the definer created by
     *                    {@link #createTypeDefiner(BiConsumer)}
     * @return the extractors
     */
    List<TypeSpecExtractor<T>> typeSpecExtractors(D typeDefiner);
}
