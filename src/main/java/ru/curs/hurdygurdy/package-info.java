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
 * Client and server code generation from an OpenAPI specification.
 *
 * <p>This package is the supported surface: {@link ru.curs.hurdygurdy.JavaCodegen}
 * and {@link ru.curs.hurdygurdy.KotlinCodegen} are the entry points,
 * {@link ru.curs.hurdygurdy.GeneratorParams} configures them, and
 * {@link ru.curs.hurdygurdy.Framework}, {@link ru.curs.hurdygurdy.Role} and
 * {@link ru.curs.hurdygurdy.JavaDtoStyle} say what to produce. Everything in the
 * sub-packages is implementation and may be rearranged between releases.
 *
 * <p>Generation runs in four stages: the specification is read and canonicalised
 * ({@code spec}), reduced to a language-neutral description of the API
 * ({@code model}), walked to produce types ({@code extract}), and written out by
 * a per-language definer ({@code emit}) using one framework's annotation
 * vocabulary ({@code binding}).
 */
package ru.curs.hurdygurdy;
