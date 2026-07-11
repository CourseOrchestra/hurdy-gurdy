package ru.curs.hurdygurdy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
    void versionOptionReflectsPomVersion() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/hurdy-gurdy-version.properties")) {
            assertThat(in).as("filtered version resource on classpath").isNotNull();
            props.load(in);
        }
        String version = props.getProperty("version");
        // Resource filtering actually ran (no unresolved Maven placeholder).
        assertThat(version).isNotNull().doesNotContain("${");

        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Main.run("--version");
        } finally {
            System.setOut(original);
        }
        assertThat(exit).isEqualTo(0);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("hurdy-gurdy " + version);
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
