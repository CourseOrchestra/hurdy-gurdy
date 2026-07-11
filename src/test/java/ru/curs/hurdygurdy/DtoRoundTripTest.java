package ru.curs.hurdygurdy;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;
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
    // Companion OpenAPI 3.1.0 spec carrying the nullable-anyOf shapes (their
    // `type: 'null'` member is 3.1-only, and swagger-parser drops allOf under
    // 3.1, so the inheritance shapes stay in the 3.0.0 spec). Generated into the
    // same dto package as SPEC (their shared Inner is byte-identical).
    private static final String SPEC_31 = "src/test/resources/roundtrip31.yaml";
    private static final String DTO_PKG = "com.example.dto";
    // ZonedDateTime* are generated Jackson (de)serializer helpers, not DTOs.
    // AnyOfHolder (top-level `anyOf` of Circle/Square $refs) is now generated as
    // a DEDUCTION-based polymorphic interface — like oneOf — so its members
    // Circle/Square `implements` it and round-trip polymorphically through it.
    // It is itself an interface (skipped by the concrete-class filter) and no
    // longer needs an explicit exclusion.
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
    // Proves the new "round-trip through EVERY polymorphic base" path fired: a
    // class that survived the round-trip through 2+ distinct bases at least once
    // (records-mode Cat/Dog: via Pet AND via Creature).
    private static int multiBasePolyRoundTrips;

    @TempDir
    private Path generated;

    private void generate(JavaDtoStyle style, boolean snake, String spec) throws Exception {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(style)
                .forceSnakeCaseForProperties(snake))
                .generate(Path.of(spec), generated);
    }

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
        generate(style, snake, SPEC);
        generate(style, snake, SPEC_31);
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

    /**
     * The generator's ONLY {@code anyOf} support is nullable-unwrap: a property
     * {@code anyOf: [X, {type: 'null'}]} must generate the plain non-null element
     * type of X, and both a populated value and an explicit {@code null} must
     * round-trip. Proven here on {@code NullableHolder} (OpenAPI 3.1.0 spec):
     * {@code nick} unwraps to {@link String}, {@code alt_inner} to {@code Inner},
     * and an instance with those fields left null serializes/deserializes back to
     * null (not to a defaulted value).
     */
    @ParameterizedTest(name = "{0} forceSnakeCase={1}")
    @MethodSource("matrix")
    void nullableAnyOfUnwrapsAndRoundTrips(JavaDtoStyle style, boolean snake) throws Exception {
        generate(style, snake, SPEC_31);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            mapper.setTypeFactory(mapper.getTypeFactory().withClassLoader(loader));
            Class<?> holder = loader.loadClass(DTO_PKG + ".NullableHolder");
            Class<?> inner = loader.loadClass(DTO_PKG + ".Inner");

            // anyOf-nullable unwrapped to the plain non-null element type.
            assertThat(propertyType(holder, "nick"))
                    .as("nick anyOf-nullable unwraps to String [%s snake=%s]", style, snake)
                    .isEqualTo(String.class);
            // Field name is camelCased only when forceSnakeCase is on; otherwise
            // the raw snake_case key is kept as the Java identifier.
            assertThat(propertyType(holder, snake ? "altInner" : "alt_inner"))
                    .as("alt_inner anyOf-nullable unwraps to Inner [%s snake=%s]", style, snake)
                    .isEqualTo(inner);

            // An explicit null in a nullable-anyOf field round-trips as null.
            String json0 = "{\"id\":\"x\"}";
            Object back = mapper.readValue(json0, holder);
            String json1 = mapper.writeValueAsString(back);
            assertThat(mapper.readTree(json1).path("nick").isNull() || mapper.readTree(json1).path("nick").isMissingNode())
                    .as("null nick stays null on the wire [%s snake=%s]: %s", style, snake, json1)
                    .isTrue();
            Object back2 = mapper.readValue(json1, holder);
            String json2 = mapper.writeValueAsString(back2);
            assertThat(mapper.readTree(json2))
                    .as("null nullable-anyOf round-trips [%s snake=%s]", style, snake)
                    .isEqualTo(mapper.readTree(json1));
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * A genuinely multi-level, hand-built recursive {@code Element} tree
     * round-trips (not just the auto-terminated depth-1 instance the generic
     * loop builds). Proves the self-{@code $ref} schema both generates a Java
     * type that can nest itself AND that a real
     * {@code root -> next: leaf, children: [child]} structure survives
     * serialize &rarr; deserialize &rarr; re-serialize with nested {@code label}
     * values intact, across the whole style &times; snake matrix.
     */
    @ParameterizedTest(name = "{0} forceSnakeCase={1}")
    @MethodSource("matrix")
    void recursiveElementTreeRoundTrips(JavaDtoStyle style, boolean snake) throws Exception {
        generate(style, snake, SPEC_31);
        Path classes = GeneratedCodeCompiler.compileJava(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            mapper.setTypeFactory(mapper.getTypeFactory().withClassLoader(loader));
            Class<?> element = loader.loadClass(DTO_PKG + ".Element");

            // root -> next: leaf(next=null, children=[])
            //      -> children: [ child(next=null, children=[]) ]
            Object leaf = newElement(element, "leaf", null, List.of());
            Object child = newElement(element, "child", null, List.of());
            Object root = newElement(element, "root", leaf, List.of(child));

            String json1 = mapper.writeValueAsString(root);
            Object back = mapper.readValue(json1, element);
            String json2 = mapper.writeValueAsString(back);
            assertThat(mapper.readTree(json2))
                    .as("recursive Element tree round-trips [%s snake=%s]", style, snake)
                    .isEqualTo(mapper.readTree(json1));

            // Nested labels survive the deserialize -> re-serialize hop, proving
            // the multi-level structure (not just the top level) round-trips.
            JsonNode tree = mapper.readTree(json2);
            assertThat(tree.path("label").asText())
                    .as("root label [%s snake=%s]", style, snake).isEqualTo("root");
            assertThat(tree.path("next").path("label").asText())
                    .as("next.label [%s snake=%s]", style, snake).isEqualTo("leaf");
            assertThat(tree.path("children").path(0).path("label").asText())
                    .as("children[0].label [%s snake=%s]", style, snake).isEqualTo("child");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * Hand-builds an {@code Element} across all three DTO styles: a record via
     * its canonical constructor (matching components by name), a bean via its
     * no-arg constructor and field injection.
     */
    private static Object newElement(Class<?> element, String label, Object next, Object children)
            throws Exception {
        if (element.isRecord()) {
            RecordComponent[] comps = element.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                args[i] = switch (comps[i].getName()) {
                    case "label" -> label;
                    case "next" -> next;
                    case "children" -> children;
                    default -> null;
                };
            }
            Constructor<?> ctor = element.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        }
        Constructor<?> ctor = element.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        setField(element, instance, "label", label);
        setField(element, instance, "next", next);
        setField(element, instance, "children", children);
        return instance;
    }

    private static void setField(Class<?> clazz, Object instance, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    /** The declared Java type of a bean field or record component named {@code name}. */
    private static Class<?> propertyType(Class<?> clazz, String name) throws Exception {
        if (clazz.isRecord()) {
            for (RecordComponent c : clazz.getRecordComponents()) {
                if (c.getName().equals(name)) {
                    return c.getType();
                }
            }
        } else {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return f.getType();
                }
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    @AfterAll
    static void bothPolymorphicPathsExercised() {
        assertThat(discriminatorPolyRoundTrips)
                .as("at least one discriminator (NAME) polymorphic round-trip")
                .isGreaterThan(0);
        assertThat(oneOfPolyRoundTrips)
                .as("at least one oneOf (DEDUCTION) polymorphic round-trip")
                .isGreaterThan(0);
        assertThat(multiBasePolyRoundTrips)
                .as("at least one class round-tripped through 2+ polymorphic bases "
                        + "(records-mode Cat/Dog via Pet AND Creature)")
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
        // A type may reach the wire through MORE THAN ONE polymorphic base: e.g.
        // records-mode Cat is `implements Pet, Creature` (name-based discriminator
        // AND deduction oneOf), and a multi-level subtype is reachable through
        // each annotated ancestor. Round-trip through EVERY such base, not just
        // the first, so "extends a class implementing an interface" (and its
        // records analogue "implements two polymorphic interfaces") is actually
        // exercised on the wire.
        int basesRoundTripped = 0;
        for (Class<?> base : polymorphicBases(clazz)) {
            String json1 = mapper.writerFor(base).writeValueAsString(instance);
            Object back = mapper.readValue(json1, base);
            if (!clazz.isInstance(back)) {
                failures.add(context(clazz, style, snake) + " polymorphic deserialize via "
                        + base.getSimpleName() + " produced " + back.getClass().getSimpleName());
                continue;
            }
            String json2 = mapper.writerFor(base).writeValueAsString(back);
            if (!mapper.readTree(json2).equals(mapper.readTree(json1))) {
                failures.add(context(clazz, style, snake) + " polymorphic round-trip via "
                        + base.getSimpleName() + " mismatch:\n  json1=" + json1 + "\n  json2=" + json2);
                continue;
            }
            recordPolymorphicKind(base);
            basesRoundTripped++;
        }
        if (basesRoundTripped >= 2) {
            multiBasePolyRoundTrips++;
        }
    }

    private static void recordPolymorphicKind(Class<?> base) {
        JsonTypeInfo info = base.getAnnotation(JsonTypeInfo.class);
        if (info != null && info.use() == JsonTypeInfo.Id.DEDUCTION) {
            oneOfPolyRoundTrips++;
        } else {
            discriminatorPolyRoundTrips++;
        }
    }

    /**
     * Every polymorphic base a class participates in: each directly-implemented
     * {@code @JsonTypeInfo} dto interface, plus its direct superclass when that
     * is a {@code @JsonTypeInfo} dto class. A class both {@code extends}ing a
     * discriminator base and {@code implements}ing a oneOf interface (or a record
     * implementing two such interfaces) yields more than one entry.
     */
    private static List<Class<?>> polymorphicBases(Class<?> clazz) {
        List<Class<?>> bases = new ArrayList<>();
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().startsWith(DTO_PKG) && iface.isAnnotationPresent(JsonTypeInfo.class)) {
                bases.add(iface);
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass.getName().startsWith(DTO_PKG)
                && superClass.isAnnotationPresent(JsonTypeInfo.class)) {
            bases.add(superClass);
        }
        return bases;
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
        return buildInstance(clazz, new HashSet<>());
    }

    /**
     * Builds a populated instance of {@code clazz}, tracking on {@code onPath}
     * the DTO classes currently under construction on this recursion path so a
     * self-referential schema (e.g. {@code Element}) terminates instead of
     * recursing forever. {@code clazz} is on the path only while its own
     * fields/components are being built (added on entry, removed on exit), so a
     * type reachable through two independent branches is still built in full —
     * this is an on-path cycle check, not a global visited set.
     */
    private Object buildInstance(Class<?> clazz, Set<Class<?>> onPath) throws Exception {
        onPath.add(clazz);
        try {
            if (clazz.isRecord()) {
                return buildRecord(clazz, onPath);
            }
            return buildBean(clazz, onPath);
        } finally {
            onPath.remove(clazz);
        }
    }

    private Object buildRecord(Class<?> clazz, Set<Class<?>> onPath) throws Exception {
        RecordComponent[] components = clazz.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            args[i] = sample(components[i].getGenericType(), onPath);
        }
        Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private Object buildBean(Class<?> clazz, Set<Class<?>> onPath) throws Exception {
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                field.set(instance, sample(field.getGenericType(), onPath));
            }
        }
        return instance;
    }

    private Object sample(Type type, Set<Class<?>> onPath) throws Exception {
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                Type elem = pt.getActualTypeArguments()[0];
                // A List<self> already on the recursion path terminates as an
                // empty list (guarantees termination for a `List<Element>`).
                if (elem instanceof Class<?> ec && onPath.contains(ec)) {
                    return List.of();
                }
                return List.of(sample(elem, onPath));
            }
            if (Map.class.isAssignableFrom(raw)) {
                Type valueType = pt.getActualTypeArguments()[1];
                if (valueType instanceof Class<?> vc && onPath.contains(vc)) {
                    return Map.of();
                }
                return Map.of("extra", sample(valueType, onPath));
            }
        }
        return sampleClass((Class<?>) type, onPath);
    }

    private Object sampleClass(Class<?> type, Set<Class<?>> onPath) throws Exception {
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
            // Cycle guard: a self-reference whose class is already under
            // construction on this path (a nullable `next: Element`) terminates
            // as null instead of recursing forever.
            if (onPath.contains(type)) {
                return null;
            }
            return buildInstance(type, onPath);
        }
        throw new IllegalStateException("No sample value for type " + type.getName());
    }
}
