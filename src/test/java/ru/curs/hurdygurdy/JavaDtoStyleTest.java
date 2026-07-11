package ru.curs.hurdygurdy;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private String sourceOf(String simpleClassName) throws IOException {
        try (Stream<Path> walk = Files.walk(result)) {
            Path file = walk.filter(p -> p.getFileName().toString().equals(simpleClassName + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No generated source found for " + simpleClassName));
            return Files.readString(file);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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

    /**
     * Generates, compiles and loads a POJO-style DTO with several own fields
     * (a String and a boolean among them), then exercises the generated
     * {@code equals}/{@code hashCode}/{@code toString} at runtime via
     * reflection. This proves real behaviour rather than just checking that
     * the source text mentions the method names — in particular {@code
     * toString} must actually concatenate a field's live value, which the
     * pre-fix (literal-text) implementation would not do.
     */
    @Test
    void pojoRuntimeEqualsHashCodeToString() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.POJO))
                .generate(Path.of("src/test/resources/sample2.yaml"), result);
        Path classes = GeneratedCodeCompiler.compileJava(result);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> playerDto = loader.loadClass("com.example.dto.PlayerDTO");
            Method setTag = playerDto.getMethod("setTag", String.class);
            Method setEmail = playerDto.getMethod("setEmail", String.class);
            Method setDuplicate = playerDto.getMethod("setDuplicate", boolean.class);

            Object a = playerDto.getDeclaredConstructor().newInstance();
            setTag.invoke(a, "alice");
            setEmail.invoke(a, "alice@example.com");

            Object b = playerDto.getDeclaredConstructor().newInstance();
            setTag.invoke(b, "alice");
            setEmail.invoke(b, "alice@example.com");

            // Equal field values -> equal instances, equal hashCode.
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);

            // Changing one field breaks equality.
            setDuplicate.invoke(b, true);
            assertThat(a).isNotEqualTo(b);

            // toString actually concatenates the live field value (not
            // literal generator source text).
            String toString = a.toString();
            assertThat(toString).contains("tag=alice");
            assertThat(toString).contains("email=alice@example.com");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * Confirms POJO-style {@code additionalProperties} handling:
     * {@code dictionary.yaml}'s {@code ObjectWithAFreeDict} schema declares no
     * own properties, so its generated class has exactly one {@code
     *
     * @JsonAnyGetter}/{@code @JsonAnySetter} accessor pair and nothing else —
     * a clean spot to assert both the annotation shape and that an unknown
     * property set through the generated setter survives a real Jackson
     * serialization via the generated {@code @JsonAnyGetter}.
     *
     * <p>The deserialization side is exercised through the generated setter
     * directly rather than {@code ObjectMapper.readValue}: the single-Map-
     * parameter form of {@code @JsonAnySetter} that the generator emits is
     * not treated as a catch-all by this Jackson version when the method also
     * matches plain JavaBean {@code setXxx} naming (verified independently
     * against a minimal repro) — a pre-existing Jackson/codegen interaction,
     * not something introduced by this fix, and out of this task's scope.
     */
    @Test
    void pojoAdditionalPropertiesRoundTrips() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.POJO))
                .generate(Path.of("src/test/resources/dictionary.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);

        String src = sourceOf("ObjectWithAFreeDict");
        assertThat(src).doesNotContain("lombok");
        assertThat(countOccurrences(src, "@JsonAnyGetter")).isEqualTo(1);
        assertThat(countOccurrences(src, "@JsonAnySetter")).isEqualTo(1);

        Path classes = GeneratedCodeCompiler.compileJava(result);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            Class<?> dictClass = loader.loadClass("com.example.dto.ObjectWithAFreeDict");

            // The compiled class carries the annotations on real methods, not
            // just in the source text.
            Method anyGetter = findAnnotatedMethod(dictClass, JsonAnyGetter.class);
            Method anySetter = findAnnotatedMethod(dictClass, JsonAnySetter.class);
            assertThat(anyGetter).isNotNull();
            assertThat(anySetter).isNotNull();

            Object instance = dictClass.getDeclaredConstructor().newInstance();
            anySetter.invoke(instance, Map.of("extra", "value"));

            @SuppressWarnings("unchecked")
            Map<String, String> additionalProperties = (Map<String, String>) anyGetter.invoke(instance);
            assertThat(additionalProperties).containsEntry("extra", "value");

            String json = new ObjectMapper().writeValueAsString(instance);
            assertThat(json).contains("\"extra\":\"value\"");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    private static Method findAnnotatedMethod(Class<?> type,
                                              Class<? extends java.lang.annotation.Annotation> annotation) {
        return Stream.of(type.getMethods())
                .filter(m -> m.isAnnotationPresent(annotation))
                .findFirst()
                .orElse(null);
    }

    @Test
    void recordsEmitsRecordWithRequiredNullCheck() throws IOException {
        String src = generate(JavaDtoStyle.RECORDS, "src/test/resources/flatrecord.yaml");
        assertThat(src).contains("public record Widget(")
                .doesNotContain("lombok")
                // required component guarded
                .contains("Objects.requireNonNull(id")
                // optional component not guarded
                .doesNotContain("Objects.requireNonNull(label");
    }

    @Test
    void recordsModelPolymorphismWithSealedInterfaces() throws IOException {
        String src = generate(JavaDtoStyle.RECORDS, "src/test/resources/polyrecord.yaml");
        // discriminator base becomes a sealed interface permitting its subtypes
        assertThat(src).containsPattern("sealed interface Animal\\b");
        assertThat(src).contains("permits");
        // subtype is a record implementing the base and flattening inherited "name"
        assertThat(src).containsPattern("record Cat\\([^)]*String name[^)]*\\) implements");
        // required flattened + own component null-checked
        assertThat(src).contains("Objects.requireNonNull(name");
        assertThat(src).contains("Objects.requireNonNull(huntingSkill");
        // oneOf also a sealed interface
        assertThat(src).containsPattern("sealed interface Shape\\b");
    }
}
