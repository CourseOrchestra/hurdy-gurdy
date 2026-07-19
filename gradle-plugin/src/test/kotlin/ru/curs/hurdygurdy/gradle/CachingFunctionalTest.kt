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

    @Test
    fun `editing an externally referenced file re-runs generation`() {
        setup()
        write("openapi/petstore.yaml", SPEC_WITH_EXTERNAL_REF)
        write("openapi/common.yaml", EXTERNAL_SPEC)
        run("generatePetstore")

        val unchanged = run("generatePetstore")
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":generatePetstore")?.outcome)

        val common = File(projectDir, "openapi/common.yaml")
        common.appendText("\n# a change in a referenced file\n")
        val afterEdit = run("generatePetstore")
        assertEquals(TaskOutcome.SUCCESS, afterEdit.task(":generatePetstore")?.outcome)
    }

    companion object {
        val SPEC_WITH_EXTERNAL_REF = """
            openapi: 3.0.0
            info: { title: t, version: "1.0" }
            paths:
              \license:
                get:
                  tags: [license]
                  operationId: getLicense
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: "common.yaml#/components/schemas/LicenseResponse"
        """.trimIndent()

        val EXTERNAL_SPEC = """
            openapi: 3.0.0
            info: { title: common, version: "1.0" }
            x-package: com.acme.common
            paths: {}
            components:
              schemas:
                LicenseResponse:
                  type: object
                  nullable: false
                  properties:
                    id: { type: string }
        """.trimIndent()
    }
}
