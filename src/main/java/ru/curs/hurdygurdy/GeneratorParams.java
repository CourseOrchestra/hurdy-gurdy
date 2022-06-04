package ru.curs.hurdygurdy;

public final class GeneratorParams {
    private final String rootPackage;
    private boolean generateResponseParameter = false;
    private boolean generateApiInterface = false;
    private boolean forceSnakeCaseForProperties = true;

    private GeneratorParams(String rootPackage) {
        this.rootPackage = rootPackage;
    }

    public GeneratorParams generateResponseParameter(boolean value) {
        this.generateResponseParameter = value;
        return this;
    }

    public GeneratorParams generateApiInterface(boolean value) {
        this.generateApiInterface = value;
        return this;
    }

    public GeneratorParams forceSnakeCaseForProperties(boolean value) {
        this.forceSnakeCaseForProperties = value;
        return this;
    }

    public String getRootPackage() {
        return rootPackage;
    }

    public boolean isGenerateResponseParameter() {
        return generateResponseParameter;
    }

    public boolean isGenerateApiInterface() {
        return generateApiInterface;
    }

    public boolean isForceSnakeCaseForProperties() {
        return forceSnakeCaseForProperties;
    }

    public static GeneratorParams rootPackage(String rootPackage) {
        return new GeneratorParams(rootPackage);
    }
}
