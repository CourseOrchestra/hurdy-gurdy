package ru.curs.hurdygurdy;

import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.zip.CRC32;

/**
 * A human-readable description of everything the generated sources depend on:
 * the plugin version, the effective plugin configuration, and the CRC32
 * checksums of the spec file and every file it (transitively) references via
 * {@code $ref: "<file>#/..."}. Stored as a {@code .properties} marker file and
 * compared via {@link Properties#equals}; any difference triggers regeneration.
 */
final class Fingerprint {
    private final Properties properties;
    private final Path markerFile;

    Fingerprint(CodegenMojo mojo) throws IOException {
        this.properties = buildProperties(mojo);
        this.markerFile = markerFile(mojo);
    }

    /**
     * True unless the marker file exists and holds exactly this fingerprint.
     * An unreadable or corrupt marker counts as changed.
     */
    boolean changed() {
        if (!Files.isReadable(markerFile)) {
            return true;
        }
        Properties stored = new Properties();
        try (InputStream is = Files.newInputStream(markerFile)) {
            stored.load(is);
        } catch (IOException e) {
            return true;
        }
        return !properties.equals(stored);
    }

    void save() throws IOException {
        Files.createDirectories(markerFile.getParent());
        try (OutputStream os = Files.newOutputStream(markerFile)) {
            properties.store(os, "hurdy-gurdy up-to-date marker");
        }
    }

    private static Properties buildProperties(CodegenMojo mojo) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("hurdy-gurdy.version", pluginVersion(mojo));
        for (Field field : configurationFields()) {
            properties.setProperty(field.getName(), fieldValue(field, mojo));
        }
        for (Map.Entry<String, Path> entry : ExternalRefs.collectSpecFiles(mojo.spec).entrySet()) {
            properties.setProperty("spec|" + entry.getKey() + "|crc32", crc32Hex(entry.getValue()));
        }
        return properties;
    }

    /**
     * Location of the up-to-date marker. Always under the build directory
     * (target/), never inside {@code outputDirectory} — the output may be a
     * source tree (e.g. src/main/java) that must not be polluted with build
     * state, and target/ is wiped by {@code mvn clean}. The file name is
     * derived from {@code outputDirectory} so that multiple executions writing
     * to different output directories do not collide.
     */
    private static Path markerFile(CodegenMojo mojo) {
        String key = crc32Hex(mojo.outputDirectory.getAbsolutePath());
        return Path.of(mojo.project.getBuild().getDirectory(), "hurdy-gurdy", key + ".properties");
    }

    private static String pluginVersion(CodegenMojo mojo) {
        // null when running outside Maven (e.g. unit tests)
        return mojo.pluginDescriptor == null ? "unknown" : mojo.pluginDescriptor.getVersion();
    }

    /**
     * All plugin parameters, discovered reflectively so that a newly added
     * parameter can never be forgotten in the fingerprint. Maven's
     * {@code @Parameter} annotation has CLASS retention and is invisible at
     * runtime, so instead we take every non-static instance field except the
     * injected Maven components ({@link MavenProject}, {@link PluginDescriptor}).
     */
    private static Field[] configurationFields() {
        return Arrays.stream(CodegenMojo.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !MavenProject.class.isAssignableFrom(f.getType()))
                .filter(f -> !PluginDescriptor.class.isAssignableFrom(f.getType()))
                .filter(f -> !"spec".equals(f.getName()))
                .sorted(Comparator.comparing(Field::getName))
                .toArray(Field[]::new);
    }

    private static String fieldValue(Field field, CodegenMojo mojo) {
        try {
            return String.valueOf(field.get(mojo));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String crc32Hex(Path file) throws IOException {
        if (!Files.isReadable(file)) {
            return "missing";
        }
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(file));
        return Long.toHexString(crc.getValue());
    }

    private static String crc32Hex(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

}
