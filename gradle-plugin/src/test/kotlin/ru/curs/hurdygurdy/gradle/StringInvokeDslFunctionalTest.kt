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
