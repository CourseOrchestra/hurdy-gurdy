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

package ru.curs.hurdygurdy.gradle

import org.gradle.api.NamedDomainObjectContainer

/**
 * The `hurdyGurdy { }` DSL. Delegates to a [NamedDomainObjectContainer] of
 * [GenerationSpec], so all the standard container methods (`create`, `register`,
 * `named`, …) work, and additionally lets a spec be declared with the concise
 * `"name" { }` syntax in the Kotlin DSL:
 *
 * ```kotlin
 * hurdyGurdy {
 *     "petstore" {
 *         spec = layout.projectDirectory.file("openapi/petstore.yaml")
 *         rootPackage = "com.acme.petstore"
 *     }
 * }
 * ```
 */
class HurdyGurdyExtension(
    specs: NamedDomainObjectContainer<GenerationSpec>,
) : NamedDomainObjectContainer<GenerationSpec> by specs {

    /** Creates and configures a spec named by the string receiver. */
    operator fun String.invoke(configure: GenerationSpec.() -> Unit): GenerationSpec =
        create(this) { it.configure() }
}
