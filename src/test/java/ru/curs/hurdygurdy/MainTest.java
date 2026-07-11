package ru.curs.hurdygurdy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @TempDir
    Path out;

    private long javaFilesIn(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    @Test
    void generatesJavaSourcesFromSpec() throws IOException {
        int exit = Main.run(
                "--spec", "src/test/resources/commonparam.yaml",
                "--root-package", "com.example",
                "--output", out.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(javaFilesIn(out)).isGreaterThan(0);
    }

    @Test
    void missingRequiredOptionFailsWithNonZeroExit() {
        int exit = Main.run(
                "--spec", "src/test/resources/commonparam.yaml",
                "--output", out.toString());
        // --root-package is required; picocli returns a non-zero usage exit code
        assertThat(exit).isNotEqualTo(0);
    }
}
