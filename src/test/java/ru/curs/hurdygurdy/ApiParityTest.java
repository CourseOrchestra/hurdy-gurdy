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

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the Java and Kotlin back ends read the <em>same</em> facts out of
 * a specification: for every generated operation, the same parameters, bound the
 * same way, with the same {@code required} flags and the same default values.
 *
 * <p>The two back ends share no code below {@code APIExtractor}: each of
 * {@code JavaAPIExtractor} and {@code KotlinAPIExtractor} carries its own
 * {@code buildSpringMethod}, {@code buildQuarkusMethod} and
 * {@code buildSpringClientMethod}, six copies of one algorithm. Nothing made them
 * agree except that they were written from each other, and nothing detected it
 * when they stopped: the approval snapshots pin each language against its own
 * past output, never against the other language, and the two suites do not even
 * use the same fixtures. That is how
 * {@code TypeDefiner.effectiveDefault} — the one method that resolves a
 * parameter's default through a {@code $ref} — came to be called from all six
 * Kotlin sites and none of the Java ones, so a Java client silently lost a
 * default that the Kotlin one honoured.
 *
 * <p>What is compared is deliberately <em>not</em> the generated text. The two
 * languages legitimately spell the same binding differently (a Kotlin type may
 * be nullable, an identifier may be back-quoted), so each generated controller
 * is reduced to the list of parameter bindings it declares, and the lists are
 * compared. A binding is the quadruple that the specification actually states:
 * where the value comes from, the name it has on the wire, whether it is
 * required, and its default.
 *
 * <p>Request bodies and multipart parts are out of scope here: their types are
 * genuinely framework- and language-dependent ({@code Resource} against
 * {@code InputStream}, {@code MultipartFile} against {@code FileUpload}), and
 * the approval snapshots already pin them per language.
 *
 * <p>Like {@link Oas31ParityTest}, parity is not correctness — two identically
 * wrong outputs pass. It is the snapshots that say what the output should be,
 * and this test that carries their verdict across the language boundary.
 */
class ApiParityTest {

    /** Fixtures that declare path, query or header parameters. */
    private static final List<String> FIXTURES = List.of(
            "browseruse.json",
            "commonparam.yaml",
            "externalnullable.yaml",
            "issue617.yaml",
            "sample1.yaml",
            "sample2.yaml",
            "twoparams.yaml",
            "typearray31.yaml");

    /**
     * The generator configurations under which the two back ends must agree.
     * Every {@code build*Method} variant of both extractors is reached at least
     * once: Spring controller, Spring client, and Quarkus (whose controller and
     * client differ only by the interface-level annotation).
     */
    private static final List<Config> CONFIGS = List.of(
            new Config(Framework.SPRING, Role.CONTROLLER, true),
            new Config(Framework.SPRING, Role.CONTROLLER, false),
            new Config(Framework.SPRING, Role.API, false),
            new Config(Framework.SPRING, Role.CLIENT, false),
            new Config(Framework.QUARKUS, Role.CONTROLLER, true),
            new Config(Framework.QUARKUS, Role.CONTROLLER, false),
            new Config(Framework.QUARKUS, Role.API, false),
            new Config(Framework.QUARKUS, Role.CLIENT, false));

    private record Config(Framework framework, Role role, boolean responseParameter) {
        @Override
        public String toString() {
            return framework + "/" + role + (responseParameter ? "/+response" : "");
        }

        GeneratorParams params() {
            return GeneratorParams.rootPackage("com.example")
                    .framework(framework)
                    .generate(role)
                    .generateResponseParameter(responseParameter)
                    // Off so that one configuration can serve every fixture: a few
                    // carry camelCase property names the check rejects outright.
                    // It has no bearing on parameter bindings either way.
                    .forceSnakeCaseForProperties(false);
        }
    }

    /**
     * One parameter of one generated method, as the generated code binds it.
     *
     * @param in       where the value comes from: {@code path}, {@code query} or {@code header}
     * @param specName the name the parameter has on the wire
     * @param required the {@code required} flag as rendered, or {@code "n/a"} where the
     *                 framework has no way to express it (JAX-RS)
     * @param defaults the default value, or {@code "<none>"} when none is emitted
     */
    private record Binding(String in, String specName, String required, String defaults) {
        @Override
        public String toString() {
            return String.format("%s '%s' required=%s default=%s", in, specName, required, defaults);
        }
    }

    private static final String NONE = "<none>";
    private static final String NOT_APPLICABLE = "n/a";

    @TempDir
    private Path temp;

    static Stream<Arguments> fixturesAndConfigs() {
        return FIXTURES.stream().flatMap(fixture ->
                CONFIGS.stream().map(config -> Arguments.of(fixture, config)));
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @MethodSource("fixturesAndConfigs")
    void javaAndKotlinBindParametersAlike(String fixture, Config config) throws IOException {
        Map<String, List<Binding>> java = generate(fixture, config, "java",
            (params, spec, out) -> new JavaCodegen(params).generate(spec, out));
        Map<String, List<Binding>> kotlin = generate(fixture, config, "kotlin",
            (params, spec, out) -> new KotlinCodegen(params).generate(spec, out));

        // Method set first: a missing method is the failure mode that a
        // binding-only comparison reports as a confusing null.
        assertThat(kotlin.keySet())
                .as("methods generated from %s [%s]", fixture, config)
                .containsExactlyInAnyOrderElementsOf(java.keySet());
        for (Map.Entry<String, List<Binding>> entry : java.entrySet()) {
            assertThat(kotlin.get(entry.getKey()))
                    .as("parameter bindings of %s, generated from %s [%s]",
                            entry.getKey(), fixture, config)
                    .containsExactlyElementsOf(entry.getValue());
        }
    }

    private interface Generation {
        void run(GeneratorParams params, Path spec, Path outputDirectory) throws IOException;
    }

    /**
     * Generates {@code fixture} and returns every generated controller method,
     * keyed by {@code Interface.method}, with the parameter bindings it declares.
     */
    private Map<String, List<Binding>> generate(String fixture, Config config,
                                                String language, Generation generation)
            throws IOException {
        Path output = Files.createDirectories(temp.resolve(language));
        generation.run(config.params(), Path.of("src/test/resources", fixture), output);

        Map<String, List<Binding>> methods = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(output)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().getFileName().toString().equals("controller"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String fileName = file.getFileName().toString();
                String interfaceName = fileName.substring(0, fileName.lastIndexOf('.'));
                methods.putAll(bindings(interfaceName, Files.readString(file)));
            }
        }
        return methods;
    }

    /** A method declaration: two-space indented, not an annotation or a brace. */
    private static final Pattern JAVA_METHOD =
            Pattern.compile("^ {2}(?![@})])\\S.*?([A-Za-z_$][\\w$]*)\\(");
    private static final Pattern KOTLIN_METHOD =
            Pattern.compile("^ {2}(?:public |abstract )*fun\\s+`?([A-Za-z_$][\\w$]*)`?\\s*\\(");

    /**
     * Every method of one generated controller, keyed {@code Interface.method},
     * with its parameter bindings in declaration order.
     *
     * <p>Works on the generated text of either language, because both poets emit
     * one method declaration per (possibly wrapped) statement and spell the
     * binding annotations identically — it is only the types and identifiers
     * around them that differ.
     */
    private static Map<String, List<Binding>> bindings(String interfaceName, String source) {
        Map<String, List<Binding>> result = new LinkedHashMap<>();
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String name = methodName(lines.get(i));
            if (name == null) {
                continue;
            }
            // A declaration wraps across lines when it is long, so take lines
            // until its parentheses balance again.
            StringBuilder declaration = new StringBuilder();
            int depth = 0;
            do {
                String line = lines.get(i);
                declaration.append(line).append(' ');
                depth += count(line, '(') - count(line, ')');
                i++;
            } while (depth > 0 && i < lines.size());
            result.put(interfaceName + "." + name, parseBindings(declaration.toString()));
        }
        return result;
    }

    private static String methodName(String line) {
        Matcher kotlin = KOTLIN_METHOD.matcher(line);
        if (kotlin.find()) {
            return kotlin.group(1);
        }
        Matcher java = JAVA_METHOD.matcher(line);
        return java.find() ? java.group(1) : null;
    }

    private static int count(String line, char c) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * The binding annotations of a declaration, in order. Spring states
     * everything in one annotation; JAX-RS states the default in a separate
     * {@code @DefaultValue} that attaches to the parameter it follows.
     */
    private static final Pattern BINDING_ANNOTATION = Pattern.compile(
            "@(PathVariable|RequestParam|RequestHeader)\\(([^)]*)\\)"
                    + "|@(PathParam|QueryParam|HeaderParam)\\(\"([^\"]*)\"\\)"
                    + "|@(DefaultValue)\\(\"([^\"]*)\"\\)");
    private static final Pattern MEMBER_NAME = Pattern.compile("name = \"([^\"]*)\"");
    private static final Pattern MEMBER_REQUIRED = Pattern.compile("required = (\\w+)");
    private static final Pattern MEMBER_DEFAULT = Pattern.compile("defaultValue = \"([^\"]*)\"");

    private static List<Binding> parseBindings(String declaration) {
        List<Binding> bindings = new ArrayList<>();
        Matcher matcher = BINDING_ANNOTATION.matcher(declaration);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                String members = matcher.group(2);
                bindings.add(new Binding(
                        springIn(matcher.group(1)),
                        member(MEMBER_NAME, members, ""),
                        member(MEMBER_REQUIRED, members, "true"),
                        member(MEMBER_DEFAULT, members, NONE)));
            } else if (matcher.group(3) != null) {
                bindings.add(new Binding(
                        jaxrsIn(matcher.group(3)), matcher.group(4), NOT_APPLICABLE, NONE));
            } else if (!bindings.isEmpty()) {
                // @DefaultValue qualifies the parameter it sits on.
                Binding last = bindings.removeLast();
                bindings.add(new Binding(last.in(), last.specName(), last.required(),
                        matcher.group(6)));
            }
        }
        return bindings;
    }

    /** A path variable is part of the URL, so it is required by construction. */
    private static String springIn(String annotation) {
        return switch (annotation) {
            case "PathVariable" -> "path";
            case "RequestParam" -> "query";
            default -> "header";
        };
    }

    private static String jaxrsIn(String annotation) {
        return switch (annotation) {
            case "PathParam" -> "path";
            case "QueryParam" -> "query";
            default -> "header";
        };
    }

    private static String member(Pattern pattern, String members, String fallback) {
        Matcher matcher = pattern.matcher(members);
        return matcher.find() ? matcher.group(1) : fallback;
    }
}
