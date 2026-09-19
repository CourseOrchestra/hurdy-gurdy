package ru.curs.hurdygurdy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TestUtils {
    static String getContent(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            return paths
                    .sorted(Comparator.comparing(Path::toString))
                    .flatMap(p -> {
                                String relative = path.relativize(p)
                                        .toString()
                                        .replace(File.separatorChar, '/');
                                relative = relative.isEmpty() ? "" : '/' + relative;
                                return Stream.concat(
                                        Stream.of(
                                                String.format("---%n"),
                                                String.format("%s%n", relative)
                                        ),
                                        readFile(p));
                            }

                    ).collect(Collectors.joining());
        }
    }


    static Stream<String> readFile(Path path) {
        String result;
        if (Files.isReadable(path)) {
            try {
                result = Files.readString(path);
            } catch (IOException e) {
                result = null;
            }
            return Stream.ofNullable(result);
        } else {
            return Stream.empty();
        }
    }
}
