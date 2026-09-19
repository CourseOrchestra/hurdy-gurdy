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

package ru.curs.hurdygurdy.emit;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeSpec;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Lombok DTOs: the accessors and value methods are left to {@code @Data}, so
 * there is nothing to emit beyond the annotations that ask for them.
 */
final class JavaLombokMembers implements JavaClassMembers {

    @Override
    public void decorateClass(TypeSpec.Builder classBuilder, boolean hasParent) {
        classBuilder.addAnnotation(Data.class);
        // @Data's implicit @EqualsAndHashCode is callSuper = false, so a
        // subtype would silently drop inherited fields from equals/hashCode
        // (two subtypes differing only in an inherited field would compare
        // equal). Base/standalone classes (superclass Object) keep plain
        // @Data — callSuper there would wrongly mix in Object's identity.
        if (hasParent) {
            classBuilder.addAnnotation(AnnotationSpec.builder(EqualsAndHashCode.class)
                    .addMember("callSuper", "$L", true).build());
        }
    }

    @Override
    public void decorateAdditionalProperties(FieldSpec.Builder field) {
        field.addAnnotation(JsonAnySetter.class)
                .addAnnotation(AnnotationSpec.builder(Getter.class)
                        .addMember("onMethod_", "@$T", JsonAnyGetter.class).build());
    }

    @Override
    public void addMembers(TypeSpec.Builder classBuilder, boolean hasParent) {
        // Nothing: @Data generates the accessors, equals, hashCode and toString.
    }
}
