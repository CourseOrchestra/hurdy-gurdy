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

public class DTOMeta {
    private final String className;
    private final String packageName;
    private final String fileName;
    private final boolean nullable;

    public DTOMeta(String className, String packageName, String fileName, boolean nullable) {
        this.className = className;
        this.packageName = packageName;
        this.fileName = fileName;
        this.nullable = nullable;
    }

    public String getClassName() {
        return className;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isNullable() {
        return nullable;
    }
}
