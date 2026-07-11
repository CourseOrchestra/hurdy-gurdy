package ru.curs.hurdygurdy;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDtoStyleTest {

    @TempDir
    Path result;

    private String generate(JavaDtoStyle style, String spec) throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(style))
                .generate(Path.of(spec), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
        return allSources();
    }

    private String allSources() throws IOException {
        try (Stream<Path> walk = Files.walk(result)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }

    @Test
    void pojoEmitsAccessorsAndValueMethodsNoLombok() throws IOException {
        String src = generate(JavaDtoStyle.POJO, "src/test/resources/sample2.yaml");
        assertThat(src).doesNotContain("lombok");
        assertThat(src).contains("public boolean equals(");
        assertThat(src).contains("public int hashCode(");
        assertThat(src).contains("public String toString(");
        // at least one JavaBean getter/setter pair
        assertThat(src).containsPattern("public \\w+ get\\w+\\(\\)");
        assertThat(src).containsPattern("public void set\\w+\\(");
    }
}
