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
