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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TestUtils {

    private TestUtils() {
    }

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
