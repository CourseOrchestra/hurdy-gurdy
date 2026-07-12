package ru.curs.hurdygurdy.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationFunctionalTest {
    @TempDir lateinit var projectDir: File

    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    private fun buildFile() = write("build.gradle.kts", """
        import ru.curs.hurdygurdy.Role

        plugins {
            java
            id("ru.curs.hurdy-gurdy")
        }

        hurdyGurdy {
            create("petstore") {
                spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                rootPackage.set("com.acme.petstore")
                generate.set(setOf(Role.CONTROLLER))
            }
        }
    """.trimIndent())

    @Test
    fun `generates java sources into the output dir`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", MINIMAL_SPEC)
        buildFile()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("generatePetstore")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePetstore")?.outcome)
        val generated = File(projectDir, "build/generated-sources/hurdy-gurdy/petstore")
        assertTrue(generated.walkTopDown().any { it.extension == "java" },
            "expected at least one generated .java file")
    }

    @Test
    fun `javaDtoStyle RECORDS emits a java record DTO`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", SPEC_WITH_SCHEMA)
        write("build.gradle.kts", """
            import ru.curs.hurdygurdy.Role
            import ru.curs.hurdygurdy.JavaDtoStyle

            plugins {
                java
                id("ru.curs.hurdy-gurdy")
            }

            hurdyGurdy {
                create("petstore") {
                    spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                    rootPackage.set("com.acme.petstore")
                    javaDtoStyle.set(JavaDtoStyle.RECORDS)
                    generate.set(setOf(Role.CONTROLLER))
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("generatePetstore")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePetstore")?.outcome)
        val pet = File(projectDir, "build/generated-sources/hurdy-gurdy/petstore")
            .walkTopDown().first { it.name == "Pet.java" }
        assertTrue(pet.readText().contains("public record Pet"),
            "expected Pet to be generated as a java record with RECORDS style")
    }

    companion object {
        val MINIMAL_SPEC = DslFunctionalTest.MINIMAL_SPEC

        val SPEC_WITH_SCHEMA = """
            openapi: 3.0.0
            info: { title: t, version: "1.0" }
            paths:
              /pet:
                get:
                  tags: [pet]
                  operationId: getPet
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { ${'$'}ref: "#/components/schemas/Pet" }
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    id: { type: integer, format: int64 }
                    name: { type: string }
        """.trimIndent()
    }
}
