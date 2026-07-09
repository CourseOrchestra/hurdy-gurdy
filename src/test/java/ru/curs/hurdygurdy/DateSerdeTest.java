package ru.curs.hurdygurdy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Functional test for the {@code ZonedDateTimeSerializer}/{@code ZonedDateTimeDeserializer}
 * classes the generator emits for {@code date-time} fields.
 *
 * <p>The snapshot tests only check the <em>text</em> of those classes. Here we
 * generate them, compile them, load them and actually run them through a Jackson
 * {@link ObjectMapper} to prove the serialization round-trips — including the
 * offset-less fallback branch (which appends {@code "Z"}) and the failure path.
 */
class DateSerdeTest {

    private static final String SPEC = "src/test/resources/dateserde.yaml";
    private static final String SER = "com.example.dto.ZonedDateTimeSerializer";
    private static final String DESER = "com.example.dto.ZonedDateTimeDeserializer";

    @TempDir
    private Path generated;

    @Test
    void javaSerdeRoundTrips() throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example").generateResponseParameter(true))
                .generate(Path.of(SPEC), generated);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            assertRoundTrips(loader);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @Test
    void kotlinSerdeRoundTrips() throws Exception {
        new KotlinCodegen(GeneratorParams.rootPackage("com.example").generateResponseParameter(true))
                .generate(Path.of(SPEC), generated);
        Path classes = GeneratedCodeCompiler.compileKotlin(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            assertRoundTrips(loader);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertRoundTrips(URLClassLoader loader) {
        JsonSerializer<ZonedDateTime> serializer = (JsonSerializer<ZonedDateTime>)
                loader.loadClass(SER).getDeclaredConstructor().newInstance();
        JsonDeserializer<ZonedDateTime> deserializer = (JsonDeserializer<ZonedDateTime>)
                loader.loadClass(DESER).getDeclaredConstructor().newInstance();

        SimpleModule module = new SimpleModule();
        module.addSerializer(ZonedDateTime.class, serializer);
        module.addDeserializer(ZonedDateTime.class, deserializer);
        ObjectMapper mapper = new ObjectMapper().registerModule(module);

        // Serialization uses ISO_OFFSET_DATE_TIME.
        ZonedDateTime withOffset =
                ZonedDateTime.of(2021, 1, 2, 10, 15, 30, 0, ZoneOffset.ofHours(1));
        String json = mapper.writeValueAsString(withOffset);
        assertThat(json).isEqualTo("\"2021-01-02T10:15:30+01:00\"");

        // Round-trip: serialized value deserializes back to the same instant.
        assertThat(mapper.readValue(json, ZonedDateTime.class)).isEqualTo(withOffset);

        // Fallback branch: a value with no offset is parsed as UTC (the
        // deserializer retries after appending "Z").
        ZonedDateTime parsedNoOffset =
                mapper.readValue("\"2021-01-02T10:15:30\"", ZonedDateTime.class);
        assertThat(parsedNoOffset)
                .isEqualTo(ZonedDateTime.of(2021, 1, 2, 10, 15, 30, 0, ZoneOffset.UTC));

        // Failure path: an unparseable value raises a Jackson error rather than
        // returning a bogus value.
        assertThrows(JsonProcessingException.class,
                () -> mapper.readValue("\"not-a-timestamp\"", ZonedDateTime.class));
    }
}
