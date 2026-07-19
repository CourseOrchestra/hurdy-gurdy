/*
 * Copyright 2026 Ivan Ponomarev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.curs.hurdygurdy;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
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
 * Kotlin counterpart of {@link DtoRoundTripTest}: proves that <em>every</em>
 * instantiable DTO the Kotlin generator emits for {@code roundtrip.yaml} /
 * {@code roundtrip31.yaml} survives a full Jackson serialize &rarr; deserialize
 * &rarr; re-serialize round-trip, for both {@code forceSnakeCase} values.
 *
 * <p>Each generated concrete Kotlin type (a {@code data class}, or a plain
 * {@code open class} intermediate) is populated by reflection through its
 * primary constructor with a sample value per parameter — including an
 * {@code "extra"} entry seeded into any {@code additionalProperties} map — then
 * round-tripped and compared as re-parsed JSON trees (never by {@code equals()},
 * so the full wire shape is checked). The seeded {@code "extra"} key proves the
 * {@code @JsonAnySetter}/{@code @JsonAnyGetter} additionalProperties map actually
 * deserializes under the Kotlin module.
 *
 * <p>Discriminator subtypes ({@code sealed class} bases, NAME) and
 * {@code oneOf}/{@code anyOf} members ({@code sealed interface} bases, DEDUCTION)
 * are additionally round-tripped through every polymorphic base they participate
 * in, exercising the {@code @JsonTypeInfo} path — including the mapping-less
 * discriminator ({@code Vehicle}) and the top-level {@code anyOf}
 * ({@code AnyOfHolder}) shapes.
 *
 * <p>Kotlin data-class binding requires {@code jackson-module-kotlin} (Jackson
 * reads the primary-constructor parameter names and default values through it);
 * {@link JavaTimeModule} handles the {@code LocalDate} ("date") fields and the
 * {@link TypeFactory#withClassLoader} call lets DEDUCTION resolve the
 * child-loaded generated classes.
 */
class KotlinRoundTripTest {

    private static final String SPEC = "src/test/resources/roundtrip.yaml";
    private static final String SPEC_31 = "src/test/resources/roundtrip31.yaml";
    private static final String DTO_PKG = "com.example.dto";
    // ZonedDateTime* are generated Jackson (de)serializer helpers, not DTOs.
    private static final Set<String> SKIP_SIMPLE_NAMES =
            Set.of("ZonedDateTimeSerializer", "ZonedDateTimeDeserializer");

    private static final UUID SAMPLE_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZonedDateTime SAMPLE_DATE_TIME =
            ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2020, 1, 2);

    // Aggregated across the matrix so one @AfterAll can prove both the name-based
    // (discriminator) and deduction-based (oneOf/anyOf) polymorphic paths, and the
    // multi-base path (Kotlin Cat/Dog via Pet AND Creature), were all exercised.
    private static int discriminatorPolyRoundTrips;
    private static int oneOfPolyRoundTrips;
    private static int multiBasePolyRoundTrips;

    @TempDir
    private Path generated;

    private void generate(boolean snake, String spec) throws Exception {
        new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(snake))
                .generate(Path.of(spec), generated);
    }

    private ObjectMapper mapperFor(URLClassLoader loader) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new KotlinModule.Builder().build())
                .registerModule(new JavaTimeModule());
        mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));
        return mapper;
    }

    @ParameterizedTest(name = "forceSnakeCase={0}")
    @ValueSource(booleans = {true, false})
    void everyKotlinDtoClassRoundTrips(boolean snake) throws Exception {
        generate(snake, SPEC);
        generate(snake, SPEC_31);
        Path classes = GeneratedCodeCompiler.compileKotlin(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            ObjectMapper mapper = mapperFor(loader);
            List<String> failures = new ArrayList<>();
            int roundTripped = 0;
            for (Class<?> clazz : concreteDtoClasses(loader)) {
                roundTripped++;
                roundTripOne(mapper, clazz, snake, failures);
            }
            assertThat(failures)
                    .as("round-trip failures for snake=%s", snake)
                    .isEmpty();
            assertThat(roundTripped)
                    .as("at least one concrete Kotlin DTO discovered for snake=%s", snake)
                    .isGreaterThan(0);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * A two-level (nested) discriminator must not double-emit an intermediate
     * base's own discriminator property. {@code Outer} (propertyName {@code kind})
     * permits {@code Middle}; {@code Middle} is ITSELF a discriminator base
     * (propertyName {@code sub_kind}) whose concrete leaf is {@code Leaf}. In the
     * Kotlin generator {@code Middle} is a {@code sealed class} whose
     * {@code sub_kind} is managed by Jackson as the {@code @JsonTypeInfo} type-id,
     * so it must NOT also appear as a data-class constructor property on
     * {@code Leaf}. The runtime-visible symptom is asserted directly: the
     * discriminator key each annotated base manages appears <em>exactly once</em>
     * on the wire; the round-trip through both {@code Outer} (resolved two levels
     * down) and {@code Middle} still reaches {@code Leaf} and survives
     * tree-equality.
     */
    @ParameterizedTest(name = "forceSnakeCase={0}")
    @ValueSource(booleans = {true, false})
    void nestedDiscriminatorNotDoubleEmitted(boolean snake) throws Exception {
        generate(snake, SPEC);
        Path classes = GeneratedCodeCompiler.compileKotlin(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            ObjectMapper mapper = mapperFor(loader);
            Class<?> leaf = loader.loadClass(DTO_PKG + ".Leaf");
            Object instance = buildInstance(leaf);

            for (String baseName : List.of("Outer", "Middle")) {
                Class<?> base = loader.loadClass(DTO_PKG + "." + baseName);
                JsonTypeInfo info = base.getAnnotation(JsonTypeInfo.class);
                assertThat(info)
                        .as("%s is an annotated polymorphic base [snake=%s]", baseName, snake)
                        .isNotNull();
                String disc = info.property();
                String json1 = mapper.writerFor(base).writeValueAsString(instance);
                assertThat(countOccurrences(json1, "\"" + disc + "\""))
                        .as("discriminator '%s' emitted exactly once via %s [snake=%s]: %s",
                                disc, baseName, snake, json1)
                        .isEqualTo(1);
                Object back = mapper.readValue(json1, base);
                assertThat(leaf.isInstance(back))
                        .as("Leaf resolves through %s [snake=%s]: got %s",
                                baseName, snake, back.getClass().getSimpleName())
                        .isTrue();
                String json2 = mapper.writerFor(base).writeValueAsString(back);
                assertThat(mapper.readTree(json2))
                        .as("nested-discriminator Leaf round-trips via %s [snake=%s]", baseName, snake)
                        .isEqualTo(mapper.readTree(json1));
            }
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * A genuinely multi-level, hand-built recursive {@code Element} tree
     * round-trips (not just the auto-terminated depth-1 instance the generic loop
     * builds). Proves the self-{@code $ref} schema generates a Kotlin type that
     * nests itself AND that a real {@code root -> next: leaf, children: [child]}
     * structure survives serialize &rarr; deserialize &rarr; re-serialize with
     * nested {@code label} values intact.
     */
    @ParameterizedTest(name = "forceSnakeCase={0}")
    @ValueSource(booleans = {true, false})
    void recursiveElementTreeRoundTrips(boolean snake) throws Exception {
        generate(snake, SPEC_31);
        Path classes = GeneratedCodeCompiler.compileKotlin(generated);
        try (URLClassLoader loader = GeneratedCodeCompiler.classLoaderFor(classes)) {
            ObjectMapper mapper = mapperFor(loader);
            Class<?> element = loader.loadClass(DTO_PKG + ".Element");

            Object leaf = newElement(element, "leaf", null, List.of());
            Object child = newElement(element, "child", null, List.of());
            Object root = newElement(element, "root", leaf, List.of(child));

            String json1 = mapper.writeValueAsString(root);
            Object back = mapper.readValue(json1, element);
            String json2 = mapper.writeValueAsString(back);
            assertThat(mapper.readTree(json2))
                    .as("recursive Element tree round-trips [snake=%s]", snake)
                    .isEqualTo(mapper.readTree(json1));

            JsonNode tree = mapper.readTree(json2);
            assertThat(tree.path("label").asText())
                    .as("root label [snake=%s]", snake).isEqualTo("root");
            assertThat(tree.path("next").path("label").asText())
                    .as("next.label [snake=%s]", snake).isEqualTo("leaf");
            assertThat(tree.path("children").path(0).path("label").asText())
                    .as("children[0].label [snake=%s]", snake).isEqualTo("child");
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * Builds an {@code Element} through its Kotlin primary constructor, matching
     * arguments by parameter <em>type</em> (String -&gt; label, the self
     * {@code Element} -&gt; next, List -&gt; children) rather than by name, since
     * Kotlin bytecode does not carry the {@code MethodParameters} attribute the
     * compiler is not asked to emit.
     */
    private static Object newElement(Class<?> element, String label, Object next, Object children)
            throws Exception {
        Constructor<?> ctor = primaryConstructor(element);
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == String.class) {
                args[i] = label;
            } else if (paramTypes[i] == element) {
                args[i] = next;
            } else if (List.class.isAssignableFrom(paramTypes[i])) {
                args[i] = children;
            }
        }
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @AfterAll
    static void allPolymorphicPathsExercised() {
        assertThat(discriminatorPolyRoundTrips)
                .as("at least one discriminator (NAME) polymorphic round-trip")
                .isGreaterThan(0);
        assertThat(oneOfPolyRoundTrips)
                .as("at least one oneOf/anyOf (DEDUCTION) polymorphic round-trip")
                .isGreaterThan(0);
        assertThat(multiBasePolyRoundTrips)
                .as("at least one class round-tripped through 2+ polymorphic bases "
                        + "(Kotlin Cat/Dog via Pet AND Creature)")
                .isGreaterThan(0);
    }

    private void roundTripOne(ObjectMapper mapper, Class<?> clazz, boolean snake, List<String> failures) {
        try {
            Object instance = buildInstance(clazz);
            directRoundTrip(mapper, clazz, instance, snake, failures);
            polymorphicRoundTrip(mapper, clazz, instance, snake, failures);
        } catch (Exception e) {
            failures.add(context(clazz, snake) + " threw " + e);
        }
    }

    private void directRoundTrip(ObjectMapper mapper, Class<?> clazz, Object instance,
                                 boolean snake, List<String> failures) throws Exception {
        String json1 = mapper.writeValueAsString(instance);
        Object back = mapper.readValue(json1, clazz);
        String json2 = mapper.writeValueAsString(back);
        if (!mapper.readTree(json2).equals(mapper.readTree(json1))) {
            failures.add(context(clazz, snake)
                    + " direct round-trip mismatch:\n  json1=" + json1 + "\n  json2=" + json2);
        }
    }

    private void polymorphicRoundTrip(ObjectMapper mapper, Class<?> clazz, Object instance,
                                      boolean snake, List<String> failures) throws Exception {
        int basesRoundTripped = 0;
        for (Class<?> base : polymorphicBases(clazz)) {
            String json1 = mapper.writerFor(base).writeValueAsString(instance);
            Object back = mapper.readValue(json1, base);
            if (!clazz.isInstance(back)) {
                failures.add(context(clazz, snake) + " polymorphic deserialize via "
                        + base.getSimpleName() + " produced " + back.getClass().getSimpleName());
                continue;
            }
            String json2 = mapper.writerFor(base).writeValueAsString(back);
            if (!mapper.readTree(json2).equals(mapper.readTree(json1))) {
                failures.add(context(clazz, snake) + " polymorphic round-trip via "
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
     * Every polymorphic base a Kotlin DTO participates in: each directly-extended
     * {@code sealed class} superclass carrying {@code @JsonTypeInfo} (NAME), plus
     * every directly-implemented dto-package {@code sealed interface} carrying
     * {@code @JsonTypeInfo} (DEDUCTION). Kotlin {@code Cat}/{@code Dog} extend the
     * {@code Pet} sealed class AND implement the {@code Creature} sealed interface,
     * yielding two entries.
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

    private static String context(Class<?> clazz, boolean snake) {
        return clazz.getSimpleName() + " [snake=" + snake + "]";
    }

    private List<Class<?>> concreteDtoClasses(URLClassLoader loader) throws Exception {
        List<Class<?>> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(generated)) {
            List<Path> files = walk
                    .filter(p -> p.toString().replace('\\', '/').contains("/dto/"))
                    .filter(p -> p.getFileName().toString().endsWith(".kt"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String simple = file.getFileName().toString().replace(".kt", "");
                if (SKIP_SIMPLE_NAMES.contains(simple)) {
                    continue;
                }
                Class<?> clazz = loader.loadClass(DTO_PKG + "." + simple);
                // Interfaces (sealed oneOf/anyOf bases), enums and abstract classes
                // (sealed discriminator bases compile to abstract) are not directly
                // instantiable — round-tripped through their concrete subtypes.
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
     * Builds a populated instance of {@code clazz} through its Kotlin primary
     * constructor, tracking on {@code onPath} the DTO classes currently under
     * construction so a self-referential schema (e.g. {@code Element}) terminates
     * instead of recursing forever ({@code clazz} is on the path only while its own
     * parameters are being built).
     */
    private Object buildInstance(Class<?> clazz, Set<Class<?>> onPath) throws Exception {
        // A Kotlin `object` singleton (emitted for an empty schema) has no usable
        // constructor — round-trip its single INSTANCE.
        Object singleton = objectInstance(clazz);
        if (singleton != null) {
            return singleton;
        }
        onPath.add(clazz);
        try {
            Constructor<?> ctor = primaryConstructor(clazz);
            Type[] genericTypes = ctor.getGenericParameterTypes();
            Object[] args = new Object[genericTypes.length];
            for (int i = 0; i < genericTypes.length; i++) {
                args[i] = sample(genericTypes[i], onPath);
            }
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } finally {
            onPath.remove(clazz);
        }
    }

    /** The INSTANCE of a Kotlin {@code object} singleton, or {@code null} if not one. */
    private static Object objectInstance(Class<?> clazz) throws Exception {
        try {
            Field instance = clazz.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(instance.getModifiers()) && instance.getType() == clazz) {
                instance.setAccessible(true);
                return instance.get(null);
            }
        } catch (NoSuchFieldException ignored) {
            // not an object singleton
        }
        return null;
    }

    /**
     * The Kotlin primary constructor: the single non-synthetic declared
     * constructor (the {@code DefaultConstructorMarker} overload Kotlin emits for
     * default-valued parameters is synthetic and skipped).
     */
    private static Constructor<?> primaryConstructor(Class<?> clazz) {
        Constructor<?> best = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isSynthetic()) {
                continue;
            }
            if (best == null || ctor.getParameterCount() > best.getParameterCount()) {
                best = ctor;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No usable constructor for " + clazz.getName());
        }
        return best;
    }

    private Object sample(Type type, Set<Class<?>> onPath) throws Exception {
        type = unwrapWildcard(type);
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                // Kotlin `List<out E>` surfaces the element as `? extends E`.
                Type elem = unwrapWildcard(pt.getActualTypeArguments()[0]);
                if (elem instanceof Class<?> ec && onPath.contains(ec)) {
                    return List.of();
                }
                return List.of(sample(elem, onPath));
            }
            if (Map.class.isAssignableFrom(raw)) {
                // Seed the additionalProperties `{"extra": ...}` so its
                // deserialization through @JsonAnySetter is exercised.
                Type valueType = unwrapWildcard(pt.getActualTypeArguments()[1]);
                if (valueType instanceof Class<?> vc && onPath.contains(vc)) {
                    return Map.of();
                }
                return Map.of("extra", sample(valueType, onPath));
            }
        }
        return sampleClass((Class<?>) type, onPath);
    }

    private static Type unwrapWildcard(Type type) {
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length > 0 ? upper[0] : Object.class;
        }
        return type;
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
