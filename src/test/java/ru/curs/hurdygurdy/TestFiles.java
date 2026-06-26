package ru.curs.hurdygurdy;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small filesystem helpers shared by the tests. */
final class TestFiles {

    private TestFiles() {
    }

    /**
     * Recursively deletes a temporary directory, children first.
     *
     * <p>{@link Files#walk(Path, java.nio.file.FileVisitOption...)} returns a
     * stream backed by an open directory handle, so it must be closed with
     * try-with-resources — leaving it open leaks a file handle and, on Windows,
     * can even prevent the directory from being deleted.
     *
     * @param dir directory to remove; ignored if {@code null} or absent
     */
    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
