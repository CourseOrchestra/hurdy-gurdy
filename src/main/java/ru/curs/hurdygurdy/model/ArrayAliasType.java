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

package ru.curs.hurdygurdy.model;

import java.util.List;

/**
 * A component schema that is a plain {@code type: array}, generated as a class
 * extending {@code ArrayList} so that it serializes as a bare JSON array.
 *
 * <p>Only reached when {@code generateAliasAsModel} is set. Otherwise such a
 * component has no class at all: it is inlined as a list at every point of use,
 * which is decided in {@link TypeModelBuilder#inlinableArrayAlias}.
 *
 * @param name            the component's name
 * @param packageName     the package it is generated into
 * @param element         the element type, or null when the schema declares no
 *                        {@code items} at all
 * @param superInterfaces fully qualified types named by {@code x-extends}
 */
public record ArrayAliasType(String name, String packageName, TypeRef element,
                             List<String> superInterfaces) implements TypeModel {
}
