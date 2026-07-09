package ru.curs.hurdygurdy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class GeneratorParams {
    private final String rootPackage;
    private boolean generateResponseParameter = false;
    private boolean forceSnakeCaseForProperties = true;
    private Framework framework = Framework.SPRING;
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

    public GeneratorParams framework(Framework value) {
        this.framework = value;
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

    public Framework getFramework() {
        return framework;
    }

    public static GeneratorParams rootPackage(String rootPackage) {
        return new GeneratorParams(rootPackage);
    }
}
