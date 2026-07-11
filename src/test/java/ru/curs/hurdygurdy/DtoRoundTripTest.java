package ru.curs.hurdygurdy;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that <em>every</em> instantiable DTO the generator emits for
 * {@code roundtrip.yaml} survives a full Jackson serialize &rarr; deserialize
 * &rarr; re-serialize round-trip, for the whole
 * {@link JavaDtoStyle} &times; {@code forceSnakeCase} matrix (3 &times; 2 = 6
 * combinations).
 *
 * <p>Each generated concrete class is populated by reflection with a sample
 * value per field/component (including an {@code "extra"} entry seeded into any
 * {@code additionalProperties} map), round-tripped, and compared as re-parsed
 * JSON trees — never by object {@code .equals()}, so inherited fields and the
 * full wire shape are checked. The seeded {@code "extra"} key is what proves
 * {@code additionalProperties} actually deserializes in every style (the POJO
 * path did not, before the field-level {@code @JsonAnySetter} fix).
 *
 * <p>Discriminator subtypes and {@code oneOf} members are additionally
 * round-tripped through their polymorphic base type to exercise the
 * {@code @JsonTypeInfo} path.
 */
class DtoRoundTripTest {

    private static final String SPEC = "src/test/resources/roundtrip.yaml";
    private static final String DTO_PKG = "com.example.dto";
    private static final Set<String> SKIP_SIMPLE_NAMES =
            Set.of("ZonedDateTimeSerializer", "ZonedDateTimeDeserializer");

    private static final UUID SAMPLE_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZonedDateTime SAMPLE_DATE_TIME =
            ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2020, 1, 2);

    // Aggregated across the whole matrix so one @AfterAll can prove both the
    // name-based (discriminator) and deduction-based (oneOf) polymorphic paths
    // were actually exercised at least once.
    private static int discriminatorPolyRoundTrips;
    private static int oneOfPolyRoundTrips;

    @TempDir
    private Path generated;

    static Stream<Arguments> matrix() {
        List<Arguments> args = new ArrayList<>();
        for (JavaDtoStyle style : JavaDtoStyle.values()) {
            args.add(Arguments.of(style, true));
            args.add(Arguments.of(style, false));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} forceSnakeCase={1}")
    @MethodSource("matrix")
    void everyDtoClassRoundTrips(JavaDtoStyle style, boolean snake) throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(style)
                .forceSnakeCaseForProperties(snake))
                .generate(Path.of(SPEC), generated);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            // JavaTimeModule: the generator emits a self-contained serializer only
            // for ZonedDateTime (its @JsonSerialize/@JsonDeserialize win over the
            // module); a bare LocalDate ("date" format) relies on jsr310, exactly
            // as a real Spring/Quarkus app's mapper does. withClassLoader lets
            // Jackson's polymorphic type resolver find the child-loaded generated
            // classes (deduction uses class-name resolution internally).
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            mapper.setTypeFactory(mapper.getTypeFactory().withClassLoader(loader));
            List<String> failures = new ArrayList<>();
            int roundTripped = 0;
            for (Class<?> clazz : concreteDtoClasses(loader)) {
                roundTripped++;
                roundTripOne(mapper, clazz, style, snake, failures);
            }
            assertThat(failures)
                    .as("round-trip failures for %s snake=%s", style, snake)
                    .isEmpty();
            assertThat(roundTripped)
                    .as("at least one concrete DTO discovered for %s snake=%s", style, snake)
                    .isGreaterThan(0);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    @AfterAll
    static void bothPolymorphicPathsExercised() {
        assertThat(discriminatorPolyRoundTrips)
                .as("at least one discriminator (NAME) polymorphic round-trip")
                .isGreaterThan(0);
        assertThat(oneOfPolyRoundTrips)
                .as("at least one oneOf (DEDUCTION) polymorphic round-trip")
                .isGreaterThan(0);
    }

    private void roundTripOne(ObjectMapper mapper, Class<?> clazz, JavaDtoStyle style,
                              boolean snake, List<String> failures) {
        try {
            Object instance = buildInstance(clazz);
            directRoundTrip(mapper, clazz, instance, style, snake, failures);
            polymorphicRoundTrip(mapper, clazz, instance, style, snake, failures);
        } catch (Exception e) {
            failures.add(context(clazz, style, snake) + " threw " + e);
        }
    }

    private void directRoundTrip(ObjectMapper mapper, Class<?> clazz, Object instance,
                                 JavaDtoStyle style, boolean snake, List<String> failures)
            throws Exception {
        String json1 = mapper.writeValueAsString(instance);
        Object back = mapper.readValue(json1, clazz);
        String json2 = mapper.writeValueAsString(back);
        if (!mapper.readTree(json2).equals(mapper.readTree(json1))) {
            failures.add(context(clazz, style, snake)
                    + " direct round-trip mismatch:\n  json1=" + json1 + "\n  json2=" + json2);
        }
    }

    private void polymorphicRoundTrip(ObjectMapper mapper, Class<?> clazz, Object instance,
                                      JavaDtoStyle style, boolean snake, List<String> failures)
            throws Exception {
        Class<?> base = polymorphicBase(clazz);
        if (base == null) {
            return;
        }
        String json1 = mapper.writerFor(base).writeValueAsString(instance);
        Object back = mapper.readValue(json1, base);
        if (!clazz.isInstance(back)) {
            failures.add(context(clazz, style, snake) + " polymorphic deserialize via "
                    + base.getSimpleName() + " produced " + back.getClass().getSimpleName());
            return;
        }
        String json2 = mapper.writerFor(base).writeValueAsString(back);
        if (!mapper.readTree(json2).equals(mapper.readTree(json1))) {
            failures.add(context(clazz, style, snake) + " polymorphic round-trip via "
                    + base.getSimpleName() + " mismatch:\n  json1=" + json1 + "\n  json2=" + json2);
            return;
        }
        recordPolymorphicKind(base);
    }

    private static void recordPolymorphicKind(Class<?> base) {
        JsonTypeInfo info = base.getAnnotation(JsonTypeInfo.class);
        if (info != null && info.use() == JsonTypeInfo.Id.DEDUCTION) {
            oneOfPolyRoundTrips++;
        } else {
            discriminatorPolyRoundTrips++;
        }
    }

    /** The polymorphic base a class participates in (a {@code @JsonTypeInfo} dto interface or superclass), or null. */
    private static Class<?> polymorphicBase(Class<?> clazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().startsWith(DTO_PKG) && iface.isAnnotationPresent(JsonTypeInfo.class)) {
                return iface;
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass.getName().startsWith(DTO_PKG)
                && superClass.isAnnotationPresent(JsonTypeInfo.class)) {
            return superClass;
        }
        return null;
    }

    private static String context(Class<?> clazz, JavaDtoStyle style, boolean snake) {
        return clazz.getSimpleName() + " [" + style + " snake=" + snake + "]";
    }

    private List<Class<?>> concreteDtoClasses(URLClassLoader loader) throws Exception {
        List<Class<?>> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(generated)) {
            List<Path> files = walk
                    .filter(p -> p.toString().replace('\\', '/').contains("/dto/"))
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String simple = file.getFileName().toString().replace(".java", "");
                if (SKIP_SIMPLE_NAMES.contains(simple)) {
                    continue;
                }
                Class<?> clazz = loader.loadClass(DTO_PKG + "." + simple);
                if (clazz.isInterface() || clazz.isEnum()
                        || Modifier.isAbstract(clazz.getModifiers())) {
                    continue;
                }
                result.add(clazz);
            }
        }
        return result;
    }

    private Object buildInstance(Class<?> clazz) throws Exception {
        if (clazz.isRecord()) {
            return buildRecord(clazz);
        }
        return buildBean(clazz);
    }

    private Object buildRecord(Class<?> clazz) throws Exception {
        RecordComponent[] components = clazz.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            args[i] = sample(components[i].getGenericType());
        }
        Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private Object buildBean(Class<?> clazz) throws Exception {
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                field.set(instance, sample(field.getGenericType()));
            }
        }
        return instance;
    }

    private Object sample(Type type) throws Exception {
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                return List.of(sample(pt.getActualTypeArguments()[0]));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return Map.of("extra", sample(pt.getActualTypeArguments()[1]));
            }
        }
        return sampleClass((Class<?>) type);
    }

    private Object sampleClass(Class<?> type) throws Exception {
        if (type == String.class) {
            return "s";
        }
        if (type == Integer.class || type == int.class) {
            return 1;
        }
        if (type == Long.class || type == long.class) {
            return 1L;
        }
        if (type == Double.class || type == double.class) {
            return 1.5d;
        }
        if (type == Float.class || type == float.class) {
            return 1.5f;
        }
        if (type == Boolean.class || type == boolean.class) {
            return true;
        }
        if (type == UUID.class) {
            return SAMPLE_UUID;
        }
        if (type == ZonedDateTime.class) {
            return SAMPLE_DATE_TIME;
        }
        if (type == LocalDate.class) {
            return SAMPLE_DATE;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.getName().startsWith(DTO_PKG)) {
            return buildInstance(type);
        }
        throw new IllegalStateException("No sample value for type " + type.getName());
    }
}
