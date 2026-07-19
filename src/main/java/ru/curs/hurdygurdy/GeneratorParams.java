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

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class GeneratorParams {
    private final String rootPackage;
    private boolean generateResponseParameter = false;
    private boolean forceSnakeCaseForProperties = true;
    private boolean generateAliasAsModel = false;
    private Framework framework = Framework.SPRING;
    private JavaDtoStyle javaDtoStyle = JavaDtoStyle.LOMBOK;
    private final EnumSet<Role> generate = EnumSet.of(Role.CONTROLLER);

    private GeneratorParams(String rootPackage) {
        this.rootPackage = rootPackage;
    }

    public GeneratorParams generateResponseParameter(boolean value) {
        this.generateResponseParameter = value;
        return this;
    }

    /**
     * Selects which interfaces to generate, replacing the current selection
     * (default: {@link Role#CONTROLLER} only).
     *
     * @param roles the roles to generate; must not be empty
     * @return this
     */
    public GeneratorParams generate(Role... roles) {
        return generate(List.of(roles));
    }

    /**
     * Selects which interfaces to generate, replacing the current selection
     * (default: {@link Role#CONTROLLER} only).
     *
     * @param roles the roles to generate; must not be empty
     * @return this
     */
    public GeneratorParams generate(Iterable<Role> roles) {
        EnumSet<Role> newSet = EnumSet.noneOf(Role.class);
        roles.forEach(newSet::add);
        if (newSet.isEmpty()) {
            throw new IllegalArgumentException("At least one role must be generated");
        }
        this.generate.clear();
        this.generate.addAll(newSet);
        return this;
    }

    /**
     * Adds {@link Role#API} to (or removes it from) the set of generated
     * interfaces.
     *
     * @param value whether to generate the {@code Api} interface
     * @return this
     * @deprecated use {@link #generate(Role...)} with {@link Role#API} instead
     */
    @Deprecated
    public GeneratorParams generateApiInterface(boolean value) {
        if (value) {
            this.generate.add(Role.API);
        } else {
            this.generate.remove(Role.API);
        }
        return this;
    }

    public GeneratorParams forceSnakeCaseForProperties(boolean value) {
        this.forceSnakeCaseForProperties = value;
        return this;
    }

    /**
     * Controls how an alias — a named component schema that is a plain
     * {@code type: array} — is generated (mirrors openapi-generator's
     * {@code generateAliasAsModel}). When {@code false} (default), the alias is
     * inlined at every point of use ({@code List<Item>}); when {@code true},
     * the alias becomes a model of its own
     * ({@code class ItemArray extends ArrayList<Item>}).
     *
     * @param value whether to generate a model class for array aliases
     * @return this
     */
    public GeneratorParams generateAliasAsModel(boolean value) {
        this.generateAliasAsModel = value;
        return this;
    }

    public GeneratorParams framework(Framework value) {
        this.framework = value;
        return this;
    }

    public GeneratorParams javaDtoStyle(JavaDtoStyle value) {
        this.javaDtoStyle = value;
        return this;
    }

    public String getRootPackage() {
        return rootPackage;
    }

    public boolean isGenerateResponseParameter() {
        return generateResponseParameter;
    }

    public Set<Role> getGenerate() {
        return Collections.unmodifiableSet(generate);
    }

    public boolean isForceSnakeCaseForProperties() {
        return forceSnakeCaseForProperties;
    }

    public boolean isGenerateAliasAsModel() {
        return generateAliasAsModel;
    }

    public Framework getFramework() {
        return framework;
    }

    public JavaDtoStyle getJavaDtoStyle() {
        return javaDtoStyle;
    }

    public static GeneratorParams rootPackage(String rootPackage) {
        return new GeneratorParams(rootPackage);
    }
}
