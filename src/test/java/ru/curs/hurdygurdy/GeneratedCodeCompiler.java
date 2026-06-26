package ru.curs.hurdygurdy;

import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test harness that verifies that the code produced by the generator actually
 * compiles. The snapshot ({@link org.approvaltests.Approvals}) tests guarantee
 * that the <em>text</em> of the generated code does not change unexpectedly;
 * this class additionally guarantees that the text is valid, compilable Java
 * and Kotlin.
 *
 * <p>Both compilers run in-process and reuse the test runtime classpath
 * ({@code java.class.path}), which already contains every dependency the
 * generated code references (Jackson, Lombok, jakarta.servlet via
 * tomcat-embed-core, spring-web and the Kotlin stdlib).
 */
final class GeneratedCodeCompiler {

    /** First four bytes of every JVM class file. */
    private static final byte[] CLASS_FILE_MAGIC = {
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE
    };

    private GeneratedCodeCompiler() {
    }

    /**
     * Compiles every {@code *.java} file found under {@code sourceRoot} and
     * fails the calling test (via {@link AssertionError}) if compilation
     * produces any error.
     *
     * @param sourceRoot directory the generator wrote its output into
     * @return directory containing the produced {@code *.class} files
     */
    static Path compileJava(Path sourceRoot) {
        List<Path> sources = listFiles(sourceRoot, ".java");
        if (sources.isEmpty()) {
            // Nothing to compile (e.g. a spec that produces only Kotlin or no
            // own types at all) — that is not an error in itself.
            return createTempDir("classes-java");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available. Tests must run on a JDK, not a JRE.");
        }

        List<File> classpath = runtimeClasspath();
        Path outDir = createTempDir("classes-java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(sources);
            // -proc:full is required so Lombok's annotation processor runs: since
            // JDK 23 implicit annotation processing is off by default.
            List<String> options = List.of("-proc:full", "-encoding", "UTF-8");
            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, diagnostics, options, null, units);
            boolean ok = task.call();
            if (!ok) {
                throw new AssertionError(
                        "Generated Java code does not compile:\n" + formatJavaDiagnostics(diagnostics));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return outDir;
    }

    /**
     * Compiles every {@code *.kt} file found under {@code sourceRoot} and fails
     * the calling test (via {@link AssertionError}) if compilation produces any
     * error.
     *
     * @param sourceRoot directory the generator wrote its output into
     * @return directory containing the produced {@code *.class} files
     */
    static Path compileKotlin(Path sourceRoot) {
        List<Path> sources = listFiles(sourceRoot, ".kt");
        Path outDir = createTempDir("classes-kotlin");
        if (sources.isEmpty()) {
            return outDir;
        }

        K2JVMCompilerArguments arguments = new K2JVMCompilerArguments();
        arguments.setClasspath(runtimeClasspath().stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(File.pathSeparator)));
        arguments.setDestination(outDir.toAbsolutePath().toString());
        arguments.setJvmTarget("17");
        // The Kotlin stdlib is already on the reused runtime classpath.
        arguments.setNoStdlib(true);
        arguments.setNoReflect(true);
        arguments.setFreeArgs(sources.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList()));

        CollectingMessageCollector messageCollector = new CollectingMessageCollector();
        ExitCode exitCode =
                new K2JVMCompiler().exec(messageCollector, Services.EMPTY, arguments);

        if (exitCode != ExitCode.OK || messageCollector.hasErrors()) {
            throw new AssertionError(
                    "Generated Kotlin code does not compile (exit code " + exitCode + "):\n"
                            + messageCollector.errors());
        }
        return outDir;
    }

    /** Compiles the generated Java and discards the resulting class files. */
    static void assertJavaCompiles(Path sourceRoot) {
        Path classes = compileJava(sourceRoot);
        try {
            assertProducedClassFiles(classes);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /** Compiles the generated Kotlin and discards the resulting class files. */
    static void assertKotlinCompiles(Path sourceRoot) {
        Path classes = compileKotlin(sourceRoot);
        try {
            assertProducedClassFiles(classes);
        } finally {
            TestFiles.deleteRecursively(classes);
        }
    }

    /**
     * Guards against a silent no-op: a "successful" compilation that actually
     * produced nothing. Asserts that at least one {@code .class} file was
     * produced and that every one of them is a real class file — i.e. starts
     * with the {@code 0xCAFEBABE} magic number (which also implies non-empty).
     */
    private static void assertProducedClassFiles(Path classesDir) {
        List<Path> classFiles = listFiles(classesDir, ".class");
        if (classFiles.isEmpty()) {
            throw new AssertionError("Compilation produced no .class files in " + classesDir
                    + " — nothing was actually compiled.");
        }
        for (Path classFile : classFiles) {
            if (!startsWithClassFileMagic(classFile)) {
                throw new AssertionError(
                        "Not a valid class file (missing 0xCAFEBABE magic): " + classFile);
            }
        }
    }

    private static boolean startsWithClassFileMagic(Path classFile) {
        byte[] header = new byte[CLASS_FILE_MAGIC.length];
        try (InputStream in = Files.newInputStream(classFile)) {
            int read = in.readNBytes(header, 0, header.length);
            return read == CLASS_FILE_MAGIC.length && Arrays.equals(header, CLASS_FILE_MAGIC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds a class loader that can load the freshly compiled classes in
     * {@code classesDir} on top of the test runtime classpath, so that
     * generated classes can be instantiated and exercised at runtime.
     */
    static URLClassLoader classLoaderFor(Path classesDir) {
        try {
            URL[] urls = {classesDir.toUri().toURL()};
            return new URLClassLoader(urls, GeneratedCodeCompiler.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<File> runtimeClasspath() {
        String classpath = System.getProperty("java.class.path");
        List<File> entries = Arrays.stream(classpath.split(File.pathSeparator))
                .filter(s -> !s.isBlank())
                .map(File::new)
                .collect(Collectors.toList());
        // Guard against surefire handing us a manifest-only booter jar: if the
        // real dependencies are missing, every compilation would fail with
        // confusing "package does not exist" errors instead of a clear message.
        boolean hasJackson = entries.stream()
                .anyMatch(f -> f.getName().contains("jackson-databind"));
        if (!hasJackson) {
            throw new IllegalStateException(
                    "Test runtime classpath does not contain the dependencies required to compile "
                            + "generated code (jackson-databind not found). Surefire must pass the full "
                            + "classpath (useManifestOnlyJar=false). Classpath was:\n" + classpath);
        }
        return entries;
    }

    private static List<Path> listFiles(Path root, String suffix) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String formatJavaDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> {
                    JavaFileObject source = d.getSource();
                    String where = source == null
                            ? ""
                            : source.getName() + ":" + d.getLineNumber() + " ";
                    return where + d.getMessage(Locale.ROOT);
                })
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Collects Kotlin compiler error messages so they can be reported on failure. */
    private static final class CollectingMessageCollector implements MessageCollector {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void clear() {
            errors.clear();
        }

        @Override
        public void report(CompilerMessageSeverity severity,
                           String message,
                           CompilerMessageSourceLocation location) {
            if (severity.isError()) {
                String where = location == null
                        ? ""
                        : location.getPath() + ":" + location.getLine() + ":" + location.getColumn() + " ";
                errors.add(where + message);
            }
        }

        @Override
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        String errors() {
            return String.join(System.lineSeparator(), errors);
        }
    }
}
