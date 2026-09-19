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

import ru.curs.hurdygurdy.CaseUtils;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Plain-Java DTOs: every accessor and value method is written out, so the
 * generated sources carry no dependency on Lombok.
 */
final class JavaPojoMembers implements JavaClassMembers {

    @Override
    public void decorateClass(TypeSpec.Builder classBuilder, boolean hasParent) {
        // Nothing: the members below are emitted explicitly.
    }

    @Override
    public void decorateAdditionalProperties(FieldSpec.Builder field) {
        // Annotate the FIELD with @JsonAnySetter (like LOMBOK). Jackson does NOT
        // treat a single-Map-parameter setter that also matches the setXxx bean
        // convention as a catch-all on readValue, so the explicit setter added
        // below must NOT carry @JsonAnySetter or unknown properties are dropped
        // on deserialization. @JsonAnyGetter stays on the explicit getter.
        field.addAnnotation(JsonAnySetter.class);
    }

    /**
     * Emits JavaBean getters/setters plus value-semantic equals/hashCode/toString
     * for every declared field. {@code equals}/{@code hashCode} chain
     * {@code super} when the class extends a generated parent, so inherited
     * fields participate; {@code toString} covers own fields only (matching
     * Lombok {@code @Data}'s toString default).
     */
    @Override
    public void addMembers(TypeSpec.Builder classBuilder, boolean hasParent) {
        TypeSpec built = classBuilder.build();
        List<FieldSpec> fields = built.fieldSpecs().stream()
                .filter(f -> f.modifiers().contains(Modifier.PRIVATE)
                        && !f.modifiers().contains(Modifier.STATIC))
                .toList();
        for (FieldSpec field : fields) {
            String capital = CaseUtils.snakeToCamel(field.name(), true);
            boolean isAdditionalProperties = "additionalProperties".equals(field.name());
            MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capital)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(field.type())
                    .addStatement("return this.$N", field.name());
            if (isAdditionalProperties) {
                getter.addAnnotation(JsonAnyGetter.class);
            }
            classBuilder.addMethod(getter.build());
            MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + capital)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(field.type(), field.name())
                    .addStatement("this.$N = $N", field.name(), field.name());
            // The additionalProperties setter is deliberately left un-annotated:
            // @JsonAnySetter sits on the field (see decorateAdditionalProperties)
            // because Jackson ignores a setXxx-named single-Map @JsonAnySetter on
            // readValue, which would drop unknown properties on deserialization.
            classBuilder.addMethod(setter.build());
        }
        addValueMethods(classBuilder, fields, hasParent);
    }

    private void addValueMethods(TypeSpec.Builder classBuilder, List<FieldSpec> fields, boolean hasParent) {
        // equals
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Object.class, "o")
                .addStatement("if (this == o) return true")
                .addStatement("if (o == null || getClass() != o.getClass()) return false");
        String cast = classBuilder.build().name();
        // Fold in the parent's fields: the getClass() check above guarantees o is
        // the same concrete type, so super's own getClass() check passes too.
        if (hasParent) {
            equals.addStatement("if (!super.equals(o)) return false");
        }
        if (fields.isEmpty()) {
            equals.addStatement("return true");
        } else {
            equals.addStatement("$L that = ($L) o", cast, cast);
            // Field names go through $N rather than into the format string: a
            // property legitimately named `$name` would otherwise have its `$n`
            // read back as a JavaPoet placeholder and blow up the format.
            String cond = fields.stream()
                    .map(f -> "$T.equals($N, that.$N)")
                    .collect(Collectors.joining("\n    && "));
            List<Object> equalsArgs = new ArrayList<>();
            for (FieldSpec field : fields) {
                equalsArgs.add(Objects.class);
                equalsArgs.add(field);
                equalsArgs.add(field);
            }
            equals.addStatement("return " + cond, equalsArgs.toArray());
        }
        classBuilder.addMethod(equals.build());
        // hashCode: seed with super.hashCode() so inherited fields contribute.
        String names = fields.stream().map(FieldSpec::name)
                .collect(Collectors.joining(", "));
        if (hasParent) {
            names = names.isEmpty() ? "super.hashCode()" : "super.hashCode(), " + names;
        }
        classBuilder.addMethod(MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addStatement("return $T.hash($L)", Objects.class, names)
                .build());
        // toString
        MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class);
        // Built as a $S/$N-interleaved format so each field value is emitted
        // via a real "+" concatenation at runtime, not baked into a single
        // escaped string literal (which $S alone over the whole body would do).
        StringBuilder format = new StringBuilder("return $S");
        List<Object> args = new ArrayList<>();
        args.add(cast + "{");
        for (int i = 0; i < fields.size(); i++) {
            FieldSpec field = fields.get(i);
            format.append(" + $S + $N");
            args.add((i == 0 ? "" : ", ") + field.name() + "=");
            args.add(field.name());
        }
        format.append(" + $S");
        args.add("}");
        toString.addStatement(format.toString(), args.toArray());
        classBuilder.addMethod(toString.build());
    }
}
