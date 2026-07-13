package ru.curs.hurdygurdy;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * Discovery of files cross-referenced from an OpenAPI spec via
 * {@code $ref: "<file>#/..."}. Used by both build plugins to determine what
 * the generated sources depend on: the Maven plugin puts the files into its
 * up-to-date fingerprint, the Gradle plugin declares them as task inputs.
 */
public final class ExternalRefs {

    private ExternalRefs() {
    }

    /**
     * The spec file plus every file transitively referenced from it via
     * {@code $ref: "<file>#/..."}. Reference file names are resolved against
     * the root spec's directory, exactly as {@link TypeDefiner} does at
     * generation time. Keys are display names (the given spec string for the
     * root, the ref file name for the rest), values are resolved paths.
     * Unreadable files are kept as keys but not scanned — generation will
     * report the missing file itself.
     *
     * @param spec path to the root spec file
     * @return display name to resolved path, the root spec first
     * @throws IOException if a referenced file cannot be read
     */
    public static Map<String, Path> collectSpecFiles(String spec) throws IOException {
        Path root = Path.of(spec);
        Map<String, Path> referenced = new TreeMap<>();
        Set<Path> visited = new HashSet<>();
        visited.add(root.toAbsolutePath().normalize());
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Path file = queue.poll();
            if (!Files.isReadable(file)) {
                continue;
            }
            for (String fileName : externalRefFileNames(Files.readString(file))) {
                Path resolved = root.resolveSibling(fileName);
                if (visited.add(resolved.toAbsolutePath().normalize())) {
                    referenced.put(fileName, resolved);
                    queue.add(resolved);
                }
            }
        }
        Map<String, Path> result = new LinkedHashMap<>();
        result.put(spec, root);
        result.putAll(referenced);
        return result;
    }

    /**
     * File names referenced via {@code $ref: "<file>#/..."} in the given spec
     * content. The document is actually parsed (YAML or JSON, same Jackson
     * mappers that back the swagger parser) and its tree walked, rather than
     * text-scanned: commented-out refs are ignored, and any scalar style is
     * supported. Unparseable content yields no refs — generation will report
     * the malformed spec itself.
     *
     * @param content the spec document text (YAML or JSON)
     * @return the referenced file names, alphabetically ordered
     */
    public static Set<String> externalRefFileNames(String content) {
        JsonNode tree;
        try {
            tree = (content.stripLeading().startsWith("{") ? Json.mapper() : Yaml.mapper())
                    .readTree(content);
        } catch (IOException e) {
            return Set.of();
        }
        Set<String> result = new TreeSet<>();
        collectRefs(tree, result);
        return result;
    }

    private static void collectRefs(JsonNode node, Set<String> result) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if ("$ref".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    addRefFileName(entry.getValue().asText(), result);
                } else {
                    collectRefs(entry.getValue(), result);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, result));
        }
    }

    private static void addRefFileName(String ref, Set<String> result) {
        Matcher matcher = TypeDefiner.FILE_NAME_PATTERN.matcher(ref);
        if (!matcher.find()) {
            return;
        }
        String fileName = matcher.group(1);
        if (!fileName.isBlank() && !fileName.contains("://")) {
            result.add(fileName);
        }
    }
}
