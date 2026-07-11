package ru.curs.hurdygurdy;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Mojo(
        name = "gen-server",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class CodegenMojo extends AbstractMojo {
    @Parameter(property = "language", defaultValue = "java")
    String language;

    @Parameter(property = "framework", defaultValue = "spring")
    String framework;

    @Parameter(property = "javaDtoStyle", defaultValue = "lombok")
    String javaDtoStyle;

    @Parameter(property = "generate", defaultValue = "controller")
    String generate;

    @Parameter(property = "spec", required = true)
    String spec;

    @Parameter(property = "rootPackage", required = true)
    String rootPackage;

    @Parameter(property = "generateResponseParameter", required = false)
    boolean generateResponseParameter = false;

    /**
     * @deprecated superseded by adding {@code api} to the {@code generate} parameter
     */
    @Deprecated
    @Parameter(property = "generateApiInterface", required = false)
    boolean generateApiInterface = false;

    @Parameter(property = "forceSnakeCaseForProperties", required = false)
    boolean forceSnakeCaseForProperties = true;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Set<Role> roles = Role.parse(generate);
        if (generateApiInterface) {
            getLog().warn("generateApiInterface is deprecated; use <generate>controller,api</generate> instead");
            roles.add(Role.API);
        }
        GeneratorParams params =
                GeneratorParams.rootPackage(rootPackage)
                        .generateResponseParameter(generateResponseParameter)
                        .forceSnakeCaseForProperties(forceSnakeCaseForProperties)
                        .framework(Framework.of(framework))
                        .javaDtoStyle(JavaDtoStyle.of(javaDtoStyle))
                        .generate(roles);
        Codegen<?> codegen =
                "java".equalsIgnoreCase(language)
                        ? new JavaCodegen(params)
                        : new KotlinCodegen(params);
        try {
            Path targetPath = getTargetPath();
            codegen.generate(Path.of(spec), targetPath);
            project.addCompileSourceRoot(targetPath.toString());
        } catch (IOException e) {
            throw new MojoExecutionException("Generation failed", e);
        }


    }

    private Path getTargetPath() throws IOException {
        Path result = Path.of(project.getBuild().getDirectory()
                + File.separator + "generated-sources" + File.separator + "openapi");
        if (!Files.exists(result)) {
            Files.createDirectories(result);
        }
        return result;
    }
}
