package ru.curs.hurdygurdy.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class DslFunctionalTest {
    @TempDir lateinit var projectDir: File

    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    @Test
    fun `registers one task per named spec`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", MINIMAL_SPEC)
        write("openapi/billing.yaml", MINIMAL_SPEC)
        write("build.gradle.kts", """
            import ru.curs.hurdygurdy.Framework
            import ru.curs.hurdygurdy.Role
            import ru.curs.hurdygurdy.gradle.Language

            plugins {
                java
                id("ru.curs.hurdy-gurdy")
            }

            hurdyGurdy {
                create("petstore") {
                    spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                    rootPackage.set("com.acme.petstore")
                    framework.set(Framework.QUARKUS)
                    language.set(Language.JAVA)
                    generate.set(setOf(Role.CONTROLLER, Role.CLIENT))
                }
                create("billing") {
                    spec.set(layout.projectDirectory.file("openapi/billing.yaml"))
                    rootPackage.set("com.acme.billing")
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--group", "hurdy-gurdy")
            .build()

        assertTrue(result.output.contains("generatePetstore"))
        assertTrue(result.output.contains("generateBilling"))
    }

    companion object {
        val MINIMAL_SPEC = """
            openapi: 3.0.0
            info: { title: t, version: "1.0" }
            paths:
              /ping:
                get:
                  tags: [ping]
                  operationId: ping
                  responses:
                    "200": { description: ok }
        """.trimIndent()
    }
}
