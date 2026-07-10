package ru.curs.hurdygurdy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the README's Kotlin usage example (e.g. Gradle's buildSrc),
 * verifying that the two-axis configuration (framework x generate) is
 * usable from Kotlin.
 */
class KotlinUsageTest {
    @TempDir
    lateinit var result: Path

    @Test
    fun readmeKotlinUsageExample() {
        val codegen = KotlinCodegen(
            GeneratorParams.rootPackage("com.example.project")
                .framework(Framework.QUARKUS)
                .generate(Role.CONTROLLER, Role.CLIENT)
        )
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result)

        val controllerPackage = result.resolve("com/example/project/controller")
        assertTrue(Files.exists(controllerPackage.resolve("PlayerController.kt")))
        assertTrue(Files.exists(controllerPackage.resolve("PlayerClient.kt")))
    }

    @Test
    fun rolesCanBePassedAsKotlinCollection() {
        val params = GeneratorParams.rootPackage("com.example.project")
            .generate(listOf(Role.CONTROLLER, Role.API))
        assertEquals(setOf(Role.CONTROLLER, Role.API), params.getGenerate())
    }
}
