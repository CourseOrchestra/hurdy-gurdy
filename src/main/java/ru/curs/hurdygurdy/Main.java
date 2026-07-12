package ru.curs.hurdygurdy;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Command-line entrypoint for the hurdy-gurdy code generator. Mirrors the
 * Maven {@code gen-server} goal: reads an OpenAPI spec and writes generated
 * Java/Kotlin sources into an output directory.
 */
@Command(
        name = "hurdy-gurdy",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "Generate Java/Kotlin client & server code from an OpenAPI spec.")
public final class Main implements Callable<Integer> {

    @Option(names = {"-s", "--spec"}, required = true,
            description = "OpenAPI spec file (YAML or JSON).")
    private Path spec;

    @Option(names = {"-p", "--root-package"}, required = true,
            description = "Root package for generated code.")
    private String rootPackage;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Output directory (created if missing).")
    private Path output;

    @Option(names = {"-l", "--language"}, defaultValue = "java",
            description = "Target language: java or kotlin (default: ${DEFAULT-VALUE}).")
    private String language;

    @Option(names = {"-f", "--framework"}, defaultValue = "spring",
            description = "Target framework: spring or quarkus (default: ${DEFAULT-VALUE}).")
    private String framework;

    @Option(names = {"-g", "--generate"}, defaultValue = "controller",
            description = "Comma-separated subset of controller,api,client "
                    + "(default: ${DEFAULT-VALUE}).")
    private String generate;

    @Option(names = "--response-parameter", negatable = true, defaultValue = "false",
            description = "Add HttpServletResponse parameter to controllers "
                    + "(default: ${DEFAULT-VALUE}).")
    private boolean responseParameter;

    // picocli negatable semantics assign `negated ? defaultValue : !defaultValue`,
    // so defaultValue="true" inverts the flag (--no-force-snake-case would enable it).
    // Use a nullable Boolean with no default instead: null means "on".
    @Option(names = "--force-snake-case", negatable = true,
            description = "Force snake_case for properties (default: true).")
    private Boolean forceSnakeCase;

    @Option(names = "--java-dto-style", defaultValue = "lombok",
            description = "Java DTO style: lombok, pojo or records "
                    + "(default: ${DEFAULT-VALUE}; ignored for Kotlin).")
    private String javaDtoStyle;

    @Override
    public Integer call() throws Exception {
        Set<Role> roles = Role.parse(generate);
        GeneratorParams params = GeneratorParams.rootPackage(rootPackage)
                .generateResponseParameter(responseParameter)
                .forceSnakeCaseForProperties(forceSnakeCase == null || forceSnakeCase)
                .framework(Framework.of(framework))
                .javaDtoStyle(JavaDtoStyle.of(javaDtoStyle))
                .generate(roles);
        Codegen<?> codegen = "java".equalsIgnoreCase(language)
                ? new JavaCodegen(params)
                : new KotlinCodegen(params);
        Files.createDirectories(output);
        codegen.generate(spec, output);
        return 0;
    }

    /**
     * Parses the given arguments and runs the generator.
     *
     * @param args command-line arguments
     * @return process exit code (0 on success)
     */
    public static int run(String... args) {
        return new CommandLine(new Main()).execute(args);
    }

    /**
     * Command-line entrypoint.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Supplies the {@code --version} string from the build-filtered
     * {@code hurdy-gurdy-version.properties} resource, so it always reflects
     * the Maven project version instead of a hardcoded literal.
     */
    static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            Properties props = new Properties();
            try (InputStream in =
                         Main.class.getResourceAsStream("/hurdy-gurdy-version.properties")) {
                if (in != null) {
                    props.load(in);
                }
            }
            return new String[] {"hurdy-gurdy " + props.getProperty("version", "unknown")};
        }
    }
}
