package ru.curs.hurdygurdy.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/** Guards the concise `"petstore" { }` string-invoke DSL documented in the README. */
class StringInvokeDslFunctionalTest {
    @TempDir lateinit var projectDir: File
    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    @Test
    fun `string-invoke DSL registers the generate task`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", DslFunctionalTest.MINIMAL_SPEC)
        write("build.gradle.kts", """
            import ru.curs.hurdygurdy.Role

            plugins {
                java
                id("ru.curs.hurdy-gurdy")
            }

            hurdyGurdy {
                "petstore" {
                    spec = layout.projectDirectory.file("openapi/petstore.yaml")
                    rootPackage = "com.acme.petstore"
                    generate = setOf(Role.CONTROLLER)
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--group", "hurdy-gurdy")
            .build()
        assertTrue(result.output.contains("generatePetstore"))
    }
}
