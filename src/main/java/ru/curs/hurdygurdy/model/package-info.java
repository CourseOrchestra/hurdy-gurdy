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
 * What the specification says, in no particular language.
 *
 * <p>Two builders, and between them the only code that reads
 * {@code io.swagger} types.
 * {@link ru.curs.hurdygurdy.model.ApiModelBuilder} reads the paths into
 * {@link ru.curs.hurdygurdy.model.InterfaceModel} and
 * {@link ru.curs.hurdygurdy.model.OperationModel} — parameters with their
 * position, identifier, {@code required} flag, resolved default and whether the
 * value can be absent; bodies as one value or a list of parts.
 * {@link ru.curs.hurdygurdy.model.TypeModelBuilder} reads the schemas into
 * {@link ru.curs.hurdygurdy.model.TypeModel} and
 * {@link ru.curs.hurdygurdy.model.TypeRef} — what shape a component has, what a
 * schema means as a type at a point of use, and what each property is called,
 * requires and defaults to.
 *
 * <p>Deliberately absent is how any of it is <em>spelled</em>. {@code byte[]}
 * against {@code ByteArray}, a boxed Java type against a nullable Kotlin one, a
 * record against a data class: those are mapping tables, they are genuinely
 * different per language, and they stay with the definers in {@code emit}.
 */
package ru.curs.hurdygurdy.model;
