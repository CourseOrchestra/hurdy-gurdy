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
 * The annotation vocabulary of one target web framework.
 *
 * <p>Only the names change between Spring Web MVC, Spring's declarative HTTP
 * interface and Quarkus; a binding supplies them and the extractor holds the
 * algorithm.
 *
 * <p>A dialect, not a role: the Spring client has its own binding because it
 * speaks {@code @GetExchange} rather than {@code @GetMapping}, while a Quarkus
 * resource and a Quarkus client share one.
 *
 * <p>One interface per language for as long as their methods return JavaPoet or
 * KotlinPoet objects; they merge once the model covers types as well as
 * operations.
 */
package ru.curs.hurdygurdy.binding;
