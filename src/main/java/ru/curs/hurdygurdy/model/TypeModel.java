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

/**
 * A type the generator has to produce a source file — or a nested declaration —
 * for.
 *
 * <p>Four shapes, and which one a schema has is a question about the
 * specification: an {@code enum} is an {@link EnumType}, a {@code type: array}
 * component an {@link ArrayAliasType}, a {@code oneOf} (or an {@code anyOf} of
 * {@code $ref}s) a {@link PolymorphicType}, and anything else an
 * {@link ObjectType}. Both back ends used to ask that question for themselves,
 * out of the schema, at the top of their own {@code getDTO}.
 */
public sealed interface TypeModel permits ObjectType, EnumType, PolymorphicType, ArrayAliasType {

    /**
     * The simple name of the generated type, which is also its file name.
     *
     * @return the simple name
     */
    String name();

    /**
     * The package the type is generated into, empty for a type nested inside
     * another.
     *
     * @return the package name
     */
    String packageName();
}
