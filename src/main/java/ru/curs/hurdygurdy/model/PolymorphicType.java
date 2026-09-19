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
 * A polymorphic base: a schema whose value is one of several named types, and
 * which is therefore generated as an interface rather than as a class.
 *
 * <p>It is an {@link ObjectType} that additionally names its members, because
 * that is what it is: everything a class is asked — what package it goes in,
 * what dictionary it carries, what its schema says — is asked of a base too, and
 * only the answer to "class or interface?" differs. A back end reads
 * {@code base} for the first set of questions and the type of this record for
 * the second.
 *
 * @param base    the object description this base shares with a plain class
 * @param members the types a value of this base may actually be
 */
public record PolymorphicType(ObjectType base, List<TypeRef> members) implements TypeModel {

    @Override
    public String name() {
        return base.name();
    }

    @Override
    public String packageName() {
        return base.packageName();
    }
}
