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

import java.util.List;

/**
 * A request body or a successful reply: one media type, carrying either a single
 * value or a list of multipart parts.
 *
 * <p>The two shapes are exclusive: a multipart body carries {@code parts}, a
 * single-part one carries {@code schema}. {@code multipart} says which, and is
 * not derived from {@code schema} being null — a single-part body whose media
 * type is declared without a schema still has to name that media type, because
 * that is what the {@code consumes} annotation is built from.
 *
 * @param mediaType the media type the body is sent as
 * @param required  whether the value is always there: a {@code required: true}
 *                  request body, or a reply, whose body a documented response
 *                  promises
 * @param multipart whether the body is sent as several named parts
 * @param schema    the schema of a single-part body, null when multipart or when
 *                  the media type declares none
 * @param parts     the parts of a multipart body, empty when single-part
 */
public record BodyModel(String mediaType, boolean required, boolean multipart,
                 Schema<?> schema, List<PartModel> parts) {
}
