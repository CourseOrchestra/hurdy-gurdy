package ru.curs.hurdygurdy.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class CachingFunctionalTest {
    @TempDir lateinit var projectDir: File

    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    private fun setup() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", DslFunctionalTest.MINIMAL_SPEC)
        write("build.gradle.kts", """
            import ru.curs.hurdygurdy.Role

            plugins {
                java
                id("ru.curs.hurdy-gurdy")
            }

            repositories { mavenCentral() }
            dependencies { implementation("org.springframework:spring-web:6.1.14") }

            hurdyGurdy {
                create("petstore") {
                    spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                    rootPackage.set("com.acme.petstore")
                    generate.set(setOf(Role.CONTROLLER))
                }
            }
        """.trimIndent())
    }

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)
        .build()

    @Test
    fun `second build is up-to-date for generate and compile`() {
        setup()
        run("compileJava")

        val second = run("compileJava")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generatePetstore")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava")?.outcome)
    }
}
