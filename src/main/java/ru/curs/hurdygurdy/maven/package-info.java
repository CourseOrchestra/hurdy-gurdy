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

/**
 * The Maven plugin.
 *
 * <p>{@link ru.curs.hurdygurdy.maven.CodegenMojo} is the Maven {@code gen-server}
 * goal and {@link ru.curs.hurdygurdy.maven.Fingerprint} is what lets it skip a
 * run whose inputs have not changed — the plugin version, the effective
 * configuration, and the checksum of the specification and every file it
 * references.
 *
 * <p>Not named {@code build}: a directory of that name is ignored by the
 * repository's {@code .gitignore}, which silently dropped a new file here.
 * The command-line entry point is {@code ru.curs.hurdygurdy.Main}, which stays
 * in the root package because the executable jar's manifest and the
 * native-image configuration both name it.
 */
package ru.curs.hurdygurdy.maven;
