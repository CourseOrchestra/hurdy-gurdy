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
 * Reading an OpenAPI document and reducing it to one canonical shape.
 *
 * <p>{@link ru.curs.hurdygurdy.spec.SchemaWalker} visits every schema a document
 * can reach; {@link ru.curs.hurdygurdy.spec.SchemaNormalizer} rewrites the
 * constructs OpenAPI 3.1 spells differently into the form the rest of the
 * generator understands, and {@link ru.curs.hurdygurdy.spec.StrayNullableCheck}
 * reports the one 3.1 keyword that is deliberately ignored.
 *
 * <p>{@link ru.curs.hurdygurdy.spec.SchemaSemantics} and
 * {@link ru.curs.hurdygurdy.spec.SchemaInheritance} answer what a schema
 * <em>says</em> — is it nullable, what does it inherit through {@code allOf} —
 * once, for every target language. Questions whose answer lives in another file
 * go through {@link ru.curs.hurdygurdy.spec.LinkedDocuments}, which is what keeps
 * them from being answered by the wrong document.
 */
package ru.curs.hurdygurdy.spec;
