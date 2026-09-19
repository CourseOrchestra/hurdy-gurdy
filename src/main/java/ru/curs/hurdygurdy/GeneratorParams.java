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

/**
 * What to generate and how. Built fluently from
 * {@link #rootPackage(String)}; every setter returns {@code this}.
 */
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

    /**
     * Whether a server method is handed the raw response object, which a
     * controller needs and a client interface has no use for.
     *
     * @param value whether to generate response-related artifacts
     * @return this
     */
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

    /**
     * Whether property names must be snake_case, camel-cased in the generated
     * code and translated back by {@code @JsonNaming} (the default).
     *
     * @param value whether to require snake_case
     * @return this
     */
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

    /**
     * The web framework whose annotations the generated interfaces carry.
     *
     * @param value the target framework
     * @return this
     */
    public GeneratorParams framework(Framework value) {
        this.framework = value;
        return this;
    }

    /**
     * The shape of the generated Java DTOs. Ignored for Kotlin, which always
     * generates data classes.
     *
     * @param value the DTO style
     * @return this
     */
    public GeneratorParams javaDtoStyle(JavaDtoStyle value) {
        this.javaDtoStyle = value;
        return this;
    }

    /**
     * The root package generated code is written into.
     *
     * @return the root package
     */
    public String getRootPackage() {
        return rootPackage;
    }

    /**
     * Whether response-related artifacts were asked for.
     *
     * @return the configured value
     */
    public boolean isGenerateResponseParameter() {
        return generateResponseParameter;
    }

    /**
     * Which interfaces to generate.
     *
     * @return the selected roles, never empty
     */
    public Set<Role> getGenerate() {
        return Collections.unmodifiableSet(generate);
    }

    /**
     * Whether property names must be snake_case.
     *
     * @return the configured value
     */
    public boolean isForceSnakeCaseForProperties() {
        return forceSnakeCaseForProperties;
    }

    /**
     * Whether an array alias becomes a model of its own.
     *
     * @return the configured value
     */
    public boolean isGenerateAliasAsModel() {
        return generateAliasAsModel;
    }

    /**
     * The target web framework.
     *
     * @return the configured framework
     */
    public Framework getFramework() {
        return framework;
    }

    /**
     * The Java DTO style.
     *
     * @return the configured style
     */
    public JavaDtoStyle getJavaDtoStyle() {
        return javaDtoStyle;
    }

    /**
     * Starts a configuration for code generated into the given root package.
     *
     * @param rootPackage the root package for generated code
     * @return a new configuration
     */
    public static GeneratorParams rootPackage(String rootPackage) {
        return new GeneratorParams(rootPackage);
    }
}
