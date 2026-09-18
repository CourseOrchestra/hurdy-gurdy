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
 * One named part of a multipart request body.
 *
 * @param name    the part's name on the wire, which is also its identifier
 * @param schema  the schema of the part's value
 * @param present whether the part is always there: the body itself must be
 *                required, and the multipart object must list this part in its
 *                own {@code required}
 */
public record PartModel(String name, Schema<?> schema, boolean present) {
}
