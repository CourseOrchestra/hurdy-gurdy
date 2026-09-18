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

package ru.curs.hurdygurdy;

import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeSpec;

/**
 * How a class-shaped DTO gets its accessors and value semantics.
 *
 * <p>The two class styles build the same fields from the same schema and differ
 * only in who writes the boilerplate: {@link JavaDtoStyle#LOMBOK} delegates it to
 * an annotation processor, {@link JavaDtoStyle#POJO} writes it out. Keeping that
 * difference behind three hooks is what leaves one class-building path instead of
 * a body threaded with style tests.
 *
 * <p>{@link JavaDtoStyle#RECORDS} is not one of these: a record is not a class
 * with accessors bolted on, it is a different shape entirely, and it is dispatched
 * before this path is reached.
 */
interface JavaClassMembers {

    /**
     * Annotations the style puts on the class itself.
     *
     * @param classBuilder the class being built
     * @param hasParent    whether it extends another generated class
     */
    void decorateClass(TypeSpec.Builder classBuilder, boolean hasParent);

    /**
     * Annotations the style puts on the {@code additionalProperties} field.
     *
     * @param field the dictionary field being built
     */
    void decorateAdditionalProperties(FieldSpec.Builder field);

    /**
     * Members the style adds once every field is in place.
     *
     * @param classBuilder the class being built
     * @param hasParent    whether it extends another generated class, whose fields
     *                     {@code equals} and {@code hashCode} must fold in
     */
    void addMembers(TypeSpec.Builder classBuilder, boolean hasParent);

    /**
     * The members for a style.
     *
     * @param style the configured DTO style
     * @return the matching implementation
     */
    static JavaClassMembers of(JavaDtoStyle style) {
        return style == JavaDtoStyle.POJO ? new JavaPojoMembers() : new JavaLombokMembers();
    }
}
