package ru.curs.hurdygurdy.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.curs.hurdygurdy.GeneratorParams
import ru.curs.hurdygurdy.JavaCodegen
import ru.curs.hurdygurdy.Role
import java.io.File
import kotlin.test.assertEquals

class DeterminismFunctionalTest {
    @TempDir lateinit var dir: File

    @Test
    fun `two generations of the same spec are byte-identical`() {
        val spec = File(dir, "petstore.yaml").apply { writeText(DslFunctionalTest.MINIMAL_SPEC) }
        val a = File(dir, "a").apply { mkdirs() }
        val b = File(dir, "b").apply { mkdirs() }

        fun gen(out: File) {
            val params = GeneratorParams.rootPackage("com.acme.petstore")
                .generate(setOf(Role.CONTROLLER))
            JavaCodegen(params).generate(spec.toPath(), out.toPath())
        }
        gen(a); gen(b)

        val filesA = a.walkTopDown().filter { it.isFile }.map { it.relativeTo(a).path to it.readBytes() }.toMap()
        val filesB = b.walkTopDown().filter { it.isFile }.map { it.relativeTo(b).path to it.readBytes() }.toMap()

        assertEquals(filesA.keys, filesB.keys, "generated file sets differ")
        filesA.forEach { (path, bytes) ->
            assertEquals(true, bytes.contentEquals(filesB[path]), "content differs for $path")
        }
    }
}
