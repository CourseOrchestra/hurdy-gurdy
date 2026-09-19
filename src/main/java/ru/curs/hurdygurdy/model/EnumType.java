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
 * An enumeration: a component that declares {@code enum}, or one written inline
 * at a point of use and generated inside the type that uses it.
 *
 * @param name        the simple name of the generated enum
 * @param packageName the package it is generated into, empty when it is nested
 *                    inside the type that declares it
 * @param constants   its constants, in the order the document lists them
 */
public record EnumType(String name, String packageName, List<EnumConstant> constants)
        implements TypeModel {

    /**
     * One enum constant, and the wire value it stands for.
     *
     * <p>A value that is not already a legal SCREAMING_SNAKE_CASE identifier is
     * normalised into one, and the original has to be pinned with
     * {@code @JsonProperty} so the constant still serializes as what the document
     * said. A value that survives normalisation unchanged needs no annotation,
     * which is why {@code wireName} is null rather than a copy of
     * {@code identifier}.
     *
     * @param identifier the generated constant name
     * @param wireName   the value to pin with {@code @JsonProperty}, or null when
     *                   the identifier already is that value
     */
    public record EnumConstant(String identifier, String wireName) {
    }
}
