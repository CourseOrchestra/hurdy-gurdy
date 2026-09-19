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
 * What the specification says about the API, in no particular language.
 *
 * <p>{@link ru.curs.hurdygurdy.model.ApiModelBuilder} is the only place on this
 * path that reads {@code io.swagger} types. It produces
 * {@link ru.curs.hurdygurdy.model.InterfaceModel} and
 * {@link ru.curs.hurdygurdy.model.OperationModel} — parameters with their
 * position, identifier, {@code required} flag, resolved default and whether the
 * value can be absent; bodies as one value or a list of parts — and the back ends
 * emit from that.
 *
 * <p>Deliberately absent is the type behind a schema: mapping one to
 * {@code byte[]} or {@code ByteArray} is the genuinely per-language step and
 * stays with the definers in {@code emit}.
 */
package ru.curs.hurdygurdy.model;
