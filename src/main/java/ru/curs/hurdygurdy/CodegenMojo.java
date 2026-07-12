package ru.curs.hurdygurdy;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

@Mojo(
        name = "gen-server",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class CodegenMojo extends AbstractMojo {
    private static final int MARKER_KEY_LENGTH = 16;

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

    @Parameter(
            defaultValue = "${project.build.directory}/generated-sources/openapi",
            property = "outputDirectory")
    File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if ("kotlin".equalsIgnoreCase(language) && !hasKotlinMavenPlugin()) {
            throw new MojoExecutionException(
                    "language is set to 'kotlin' but the kotlin-maven-plugin "
                    + "(org.jetbrains.kotlin:kotlin-maven-plugin) is not configured in this "
                    + "project; the generated Kotlin sources would never be compiled");
        }
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
            Path targetPath = outputDirectory.toPath();
            Files.createDirectories(targetPath);
            Path marker = markerFile();
            String fingerprint = fingerprint(roles);
            if (Files.exists(marker) && fingerprint.equals(Files.readString(marker))) {
                getLog().info("hurdy-gurdy: generated sources are up to date, skipping generation");
            } else {
                codegen.generate(Path.of(spec), targetPath);
                Files.writeString(marker, fingerprint);
            }
            project.addCompileSourceRoot(targetPath.toString());
        } catch (IOException e) {
            throw new MojoExecutionException("Generation failed", e);
        }
    }

    /**
     * Location of the up-to-date marker. Always under the build directory
     * (target/), never inside {@code outputDirectory} — the output may be a
     * source tree (e.g. src/main/java) that must not be polluted with build
     * state, and target/ is wiped by {@code mvn clean}. The file name is
     * derived from {@code outputDirectory} so that multiple executions writing
     * to different output directories do not collide.
     */
    private Path markerFile() throws IOException {
        Path dir = Path.of(project.getBuild().getDirectory(), "hurdy-gurdy");
        Files.createDirectories(dir);
        String key = sha256Hex(outputDirectory.getAbsolutePath()
                .getBytes(StandardCharsets.UTF_8)).substring(0, MARKER_KEY_LENGTH);
        return dir.resolve(key + ".hash");
    }

    private String fingerprint(Set<Role> roles) throws IOException {
        MessageDigest md = newSha256();
        md.update(Files.readAllBytes(Path.of(spec)));
        String config = String.join("|",
                rootPackage,
                language,
                framework,
                javaDtoStyle,
                roles.toString(),
                String.valueOf(generateResponseParameter),
                String.valueOf(forceSnakeCaseForProperties),
                outputDirectory.toString());
        md.update(config.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    private boolean hasKotlinMavenPlugin() {
        for (Plugin plugin : project.getBuildPlugins()) {
            if ("org.jetbrains.kotlin".equals(plugin.getGroupId())
                    && "kotlin-maven-plugin".equals(plugin.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
