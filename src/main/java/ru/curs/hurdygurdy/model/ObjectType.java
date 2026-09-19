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

package ru.curs.hurdygurdy.model;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

/**
 * A type generated as a class, a record or a data class: a component schema that
 * is neither an enumeration nor an array alias.
 *
 * <p>Its {@code schema} is still here, and deliberately so. A property is
 * described once, by {@link TypeModelBuilder#property}, and both back ends read
 * that description — but <em>which</em> properties a generated type declares is
 * not one question with one answer. The Java class styles emit the schema's own
 * properties and inherit the rest through {@code extends}; the Java records
 * style flattens the whole {@code allOf} chain into one component list; Kotlin
 * re-declares the inherited ones as {@code override} and forwards them to the
 * base constructor. Three different selections, each correct for the shape it
 * produces. Collapsing them into one list of properties is a change of output,
 * not a move, so the selection stays with the back end and the schema comes
 * along for it to make.
 *
 * @param name                 the simple name of the generated type
 * @param packageName          the package it is generated into
 * @param document             the document the schema was declared in, needed to
 *                             follow a {@code $ref} out of it
 * @param schema               the component schema, as written
 * @param additionalProperties the value type of the dictionary the schema
 *                             declares with {@code additionalProperties}, or
 *                             null when it declares none
 */
public record ObjectType(String name, String packageName, OpenAPI document, Schema<?> schema,
                         TypeRef additionalProperties) implements TypeModel {
}
