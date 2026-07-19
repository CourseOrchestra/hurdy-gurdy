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

class SourceSetFunctionalTest {
    @TempDir lateinit var projectDir: File

    private fun write(name: String, content: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(content)

    @Test
    fun `compileJava consumes generated sources and depends on the generate task`() {
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

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("compileJava")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePetstore")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome)
    }
}
