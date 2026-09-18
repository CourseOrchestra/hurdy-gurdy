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

import io.swagger.v3.oas.models.media.Schema;

/**
 * One path, query or header parameter of an operation.
 *
 * @param specName     the name the parameter has on the wire
 * @param identifier   the generated parameter name
 * @param in           where the value comes from
 * @param schema       the schema of the value
 * @param required     whether the document declares the parameter required
 * @param defaultValue the default that applies, resolved through any
 *                     {@code $ref}, or null when it has none
 * @param present      whether the value is always there by the time the method
 *                     is called: a path variable is part of the URL, a required
 *                     parameter is demanded, and one with a default has it
 *                     substituted by the framework. A target language with
 *                     nullable types reads this; one without it ignores it.
 */
public record ParameterModel(String specName, String identifier, ParameterModel.In in, Schema<?> schema,
                      boolean required, String defaultValue, boolean present) {

    /** Where a parameter's value comes from. */
    enum In {
        /** Part of the URL path. */
        PATH,
        /** A query-string parameter. */
        QUERY,
        /** A request header. */
        HEADER
    }
}
