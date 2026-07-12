package ru.curs.hurdygurdy;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodegenMojoTest {

    private CodegenMojo newMojo(Path buildDir, Path outputDir) {
        CodegenMojo mojo = new CodegenMojo();
        mojo.language = "java";
        mojo.framework = "spring";
        mojo.javaDtoStyle = "lombok";
        mojo.generate = "controller";
        mojo.spec = "src/test/resources/sample1.yaml";
        mojo.rootPackage = "com.example";
        mojo.forceSnakeCaseForProperties = true;
        mojo.outputDirectory = outputDir.toFile();
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(buildDir.toString());
        project.setBuild(build);
        mojo.project = project;
        return mojo;
    }

    private long javaFileCount(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    private Path firstJavaFile(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).findFirst().orElseThrow();
        }
    }

    private boolean markerExists(Path buildDir) throws Exception {
        Path dir = buildDir.resolve("hurdy-gurdy");
        if (!Files.exists(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(p -> p.toString().endsWith(".hash"));
        }
    }

    @Test
    void generatesIntoConfiguredOutputDirectory(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);

        mojo.execute();

        assertThat(javaFileCount(out)).isGreaterThan(0);
        assertThat(markerExists(tmp)).isTrue();
    }

    @Test
    void secondRunSkipsWhenUnchanged(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);
        mojo.execute();
        Path generated = firstJavaFile(out);
        Files.delete(generated);

        mojo.execute(); // unchanged fingerprint -> skip, does not regenerate

        assertThat(generated).doesNotExist();
        assertThat(markerExists(tmp)).isTrue();
    }

    @Test
    void markerLivesUnderBuildDirNotOutputDir(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("src-main-java"); // output is a "source tree"
        CodegenMojo mojo = newMojo(tmp, out);

        mojo.execute();

        // marker is under the build dir, and NOT polluting the output/source tree
        assertThat(markerExists(tmp)).isTrue();
        try (Stream<Path> s = Files.walk(out)) {
            assertThat(s.noneMatch(p -> p.toString().endsWith(".hash"))).isTrue();
        }
    }

    @Test
    void changingConfigTriggersRegeneration(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);
        mojo.execute();
        Path generated = firstJavaFile(out);
        Files.delete(generated);

        mojo.generateResponseParameter = true; // config change -> new fingerprint
        mojo.execute();

        assertThat(javaFileCount(out)).isGreaterThan(0);
        assertThat(generated).exists();
    }
}
