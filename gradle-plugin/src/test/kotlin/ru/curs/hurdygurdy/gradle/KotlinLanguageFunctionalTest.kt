package ru.curs.hurdygurdy.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinLanguageFunctionalTest {
    @TempDir lateinit var projectDir: File

    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    @Test
    fun `language KOTLIN wires generated sources into compileKotlin`() {
        write(
            "settings.gradle.kts", """
                rootProject.name = "consumer"
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
            """.trimIndent()
        )
        write("openapi/petstore.yaml", DslFunctionalTest.MINIMAL_SPEC)
        write(
            "build.gradle.kts", """
                import ru.curs.hurdygurdy.Role
                import ru.curs.hurdygurdy.gradle.Language

                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("ru.curs.hurdy-gurdy")
                }

                repositories { mavenCentral() }
                dependencies {
                    implementation("org.springframework:spring-web:6.1.14")
                    implementation(kotlin("stdlib"))
                }

                hurdyGurdy {
                    create("petstore") {
                        spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                        rootPackage.set("com.acme.petstore")
                        language.set(Language.KOTLIN)
                        generate.set(setOf(Role.CONTROLLER))
                    }
                }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("compileKotlin")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePetstore")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)

        val generatedDir = File(projectDir, "build/generated-sources/hurdy-gurdy/petstore")
        val kotlinFiles = generatedDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(kotlinFiles.isNotEmpty(), "Expected at least one generated .kt file under $generatedDir")
    }

    @Test
    fun `language KOTLIN without the Kotlin plugin fails with a clear error`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", DslFunctionalTest.MINIMAL_SPEC)
        write(
            "build.gradle.kts", """
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
                        language.set(Language.KOTLIN)
                        generate.set(setOf(Role.CONTROLLER))
                    }
                }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("compileJava")
            .buildAndFail()

        assertTrue(
            result.output.contains("the Kotlin JVM plugin (org.jetbrains.kotlin.jvm) is not applied"),
            "Expected failure output to mention the missing Kotlin JVM plugin, got:\n${result.output}"
        )
    }

    @Test
    fun `unset spec properties resolve to their conventions at task execution time`() {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"")
        write("openapi/petstore.yaml", DslFunctionalTest.MINIMAL_SPEC)
        write(
            "build.gradle.kts", """
                plugins {
                    java
                    id("ru.curs.hurdy-gurdy")
                }

                hurdyGurdy {
                    create("petstore") {
                        spec.set(layout.projectDirectory.file("openapi/petstore.yaml"))
                        rootPackage.set("com.acme.petstore")
                    }
                }

                tasks.register("verifyConventions") {
                    dependsOn("generatePetstore")
                    val generateTask = tasks.named("generatePetstore", ru.curs.hurdygurdy.gradle.GenerateTask::class.java)
                    doLast {
                        val task = generateTask.get()
                        val framework = task.framework.get()
                        val generate = task.generate.get()
                        println("CONVENTION_FRAMEWORK=${'$'}framework")
                        println("CONVENTION_GENERATE=${'$'}generate")
                        check(framework == ru.curs.hurdygurdy.Framework.SPRING) {
                            "expected default framework SPRING but was ${'$'}framework"
                        }
                        check(generate == setOf(ru.curs.hurdygurdy.Role.CONTROLLER)) {
                            "expected default generate [CONTROLLER] but was ${'$'}generate"
                        }
                    }
                }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("verifyConventions")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyConventions")?.outcome)
        assertTrue(result.output.contains("CONVENTION_FRAMEWORK=SPRING"))
        assertTrue(result.output.contains("CONVENTION_GENERATE=[CONTROLLER]"))
    }
}
