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

/**
 * The kind of generated type, which decides the subpackage it is written to.
 */
public enum ClassCategory {
    /**
     * Data transition object class.
     */
    DTO("dto"),
    /**
     * Controller (api) class.
     */
    CONTROLLER("controller");

    private final String packageName;

    ClassCategory(String packageName) {
        this.packageName = packageName;
    }

    /**
     * The subpackage, below the configured root package, that this kind of type
     * is written to.
     *
     * @return the subpackage name
     */
    public String getPackageName() {
        return packageName;
    }
}
