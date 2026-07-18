package ru.curs.hurdygurdy;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CodegenMojoTest {

    public static final Pattern FILE_CHECKSUM = Pattern.compile("[0-9a-f]{8}");

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
            return s.anyMatch(p -> p.toString().endsWith(".properties"));
        }
    }

    private Properties fingerprintProperties(CodegenMojo mojo, Path buildDir) throws Exception {
        new Fingerprint(mojo).save();
        Path marker;
        try (Stream<Path> s = Files.walk(buildDir.resolve("hurdy-gurdy"))) {
            marker = s.filter(p -> p.toString().endsWith(".properties")).findFirst().orElseThrow();
        }
        Properties stored = new Properties();
        try (InputStream is = Files.newInputStream(marker)) {
            stored.load(is);
        }
        return stored;
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
            assertThat(s.noneMatch(p -> p.toString().endsWith(".properties"))).isTrue();
        }
    }

    @Test
    void changingExternallyReferencedFileTriggersRegeneration(@TempDir Path tmp) throws Exception {
        // copy the spec and the file it $refs into a writable location
        Path specDir = tmp.resolve("specs");
        Files.createDirectories(specDir);
        Path mainSpec = specDir.resolve("sample1.yaml");
        Path externalSpec = specDir.resolve("externalfile.yaml");
        Files.copy(Path.of("src/test/resources/sample1.yaml"), mainSpec);
        Files.copy(Path.of("src/test/resources/externalfile.yaml"), externalSpec);
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);
        mojo.spec = mainSpec.toString();
        mojo.execute();
        Path generated = firstJavaFile(out);
        Files.delete(generated);

        Files.writeString(externalSpec,
                Files.readString(externalSpec) + "\n# a change in a referenced file\n");
        mojo.execute(); // referenced file changed -> new fingerprint -> regenerate

        assertThat(generated).exists();
    }

    @Test
    void fingerprintIsHumanReadableProperties(@TempDir Path tmp) throws Exception {
        CodegenMojo mojo = newMojo(tmp, tmp.resolve("gen"));
        Properties stored = fingerprintProperties(mojo, tmp);
        //  stored.store(System.out, null);
        assertThat(stored.getProperty("hurdy-gurdy.version")).isEqualTo("unknown");
        assertThat(stored.getProperty("language")).isEqualTo("java");
        assertThat(stored.getProperty("framework")).isEqualTo("spring");
        assertThat(stored.getProperty("javaDtoStyle")).isEqualTo("lombok");
        assertThat(stored.getProperty("generate")).isEqualTo("controller");
        assertThat(stored.getProperty("rootPackage")).isEqualTo("com.example");
        assertThat(stored.getProperty("generateResponseParameter")).isEqualTo("false");
        assertThat(stored.getProperty("forceSnakeCaseForProperties")).isEqualTo("true");
        assertThat(stored.getProperty("generateAliasAsModel")).isEqualTo("false");
        assertThat(stored.getProperty("outputDirectory")).isNotBlank();
        assertThat(stored.getProperty("spec|src/test/resources/sample1.yaml|crc32")).matches(FILE_CHECKSUM);
        // files transitively referenced via $ref are part of the fingerprint
        assertThat(stored.getProperty("spec|externalfile.yaml|crc32")).matches(FILE_CHECKSUM);
    }

    @Test
    void commentedOutRefsAreNotPartOfTheFingerprint(@TempDir Path tmp) throws Exception {
        Path specDir = tmp.resolve("specs");
        Files.createDirectories(specDir);
        Path mainSpec = specDir.resolve("sample1.yaml");
        String content = Files.readString(Path.of("src/test/resources/sample1.yaml"))
                + "\n# $ref: 'ghost.yaml#/components/schemas/Ghost'\n";
        Files.writeString(mainSpec, content);
        Files.copy(Path.of("src/test/resources/externalfile.yaml"), specDir.resolve("externalfile.yaml"));
        CodegenMojo mojo = newMojo(tmp, tmp.resolve("gen"));
        mojo.spec = mainSpec.toString();

        Properties stored = fingerprintProperties(mojo, tmp);

        // real ref: tracked
        assertThat(stored.getProperty("spec|externalfile.yaml|crc32")).matches(FILE_CHECKSUM);
        // commented-out ref: ignored
        assertThat(stored.stringPropertyNames()).noneMatch(name -> name.contains("ghost.yaml"));
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

    @Test
    void kotlinWithKotlinMavenPluginProceeds(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        CodegenMojo mojo = newMojo(tmp, out);
        mojo.language = "kotlin";
        Plugin kotlin = new Plugin();
        kotlin.setGroupId("org.jetbrains.kotlin");
        kotlin.setArtifactId("kotlin-maven-plugin");
        mojo.project.getBuild().getPlugins().add(kotlin);

        mojo.execute();

        try (Stream<Path> s = Files.walk(out)) {
            assertThat(s.filter(p -> p.toString().endsWith(".kt")).count()).isGreaterThan(0);
        }
    }
}
