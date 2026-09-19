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

/**
 * Walking the model to produce generated types.
 *
 * <p>{@link ru.curs.hurdygurdy.extract.DTOExtractor} generates one class per
 * entry in {@code components/schemas};
 * {@link ru.curs.hurdygurdy.extract.APIExtractor} generates one interface per
 * tag, with one method per operation. Both delegate the shape of a type to a
 * definer in {@code emit}.
 *
 * <p>The per-language subclasses hold only what genuinely differs: the JavaPoet
 * or KotlinPoet builder being filled. Which annotations go on it is a binding's
 * business, and what the specification means is settled before they are reached.
 */
package ru.curs.hurdygurdy.extract;
