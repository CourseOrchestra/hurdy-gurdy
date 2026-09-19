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
 * Turning a schema into a generated type, for one target language.
 *
 * <p>{@link ru.curs.hurdygurdy.emit.TypeDefiner} holds what needs the generator's
 * configuration to answer — the package a {@code $ref} resolves into, whether an
 * array alias is inlined, whether a property name must be pinned with
 * {@code @JsonProperty} — and its two subclasses map a schema onto the type
 * system they target. That mapping is the one step that cannot be shared:
 * {@code byte[]} against {@code ByteArray}, a boxed Java type against a nullable
 * Kotlin one.
 *
 * <p>For Java the shape of a DTO is a further choice, and
 * {@link ru.curs.hurdygurdy.emit.JavaClassMembers} carries it: Lombok leaves the
 * accessors to {@code @Data}, the plain style writes them out, and records are a
 * different shape again.
 */
package ru.curs.hurdygurdy.emit;
