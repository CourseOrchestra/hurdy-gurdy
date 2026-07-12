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

    @Test
    void generatesIntoConfiguredOutputDirectory(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);

        mojo.execute();

        assertThat(javaFileCount(out)).isGreaterThan(0);
    }
}
