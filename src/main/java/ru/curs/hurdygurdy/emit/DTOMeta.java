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

package ru.curs.hurdygurdy.emit;

/**
 * Where the class generated for a referenced component lives, and whether it
 * admits null.
 *
 * @param className   the simple name of the generated class
 * @param packageName the root package it is generated into, which for a
 *                    cross-file reference comes from that file's
 *                    {@code x-package}
 * @param fileName    the file the component is declared in, empty for a
 *                    same-file reference
 * @param nullable    whether the component permits a null value
 */
public record DTOMeta(
        String className,
        String packageName,
        String fileName,
        boolean nullable) {
}
