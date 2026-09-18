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

import io.swagger.v3.oas.models.PathItem;

import java.util.List;

/**
 * One operation, read out of the specification and reduced to the facts a
 * generated method needs.
 *
 * <p>Everything here is language- and framework-neutral: it is what the document
 * says, not how any target spells it. The one thing deliberately left
 * unresolved is the {@code Schema} behind a value — turning that into a type is
 * the one genuinely per-language step, and it stays with the type definers.
 *
 * @param id             the method name: the {@code operationId}, or one derived
 *                       from the path and verb
 * @param httpMethod     the HTTP verb
 * @param path           the path the operation is declared under
 * @param response       the successful reply, or null when there is no body
 * @param body           the request body, or null when the operation takes none
 * @param parameters     path, query and header parameters, in that order
 * @param includeRequest whether {@code x-include-request} asked for the raw
 *                       request object
 */
public record OperationModel(String id, PathItem.HttpMethod httpMethod, String path,
                      BodyModel response, BodyModel body,
                      List<ParameterModel> parameters, boolean includeRequest) {
}
