package ru.curs.hurdygurdy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import ru.curs.hurdygurdy.Codegen
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.GeneratorParams
import ru.curs.hurdygurdy.JavaCodegen
import ru.curs.hurdygurdy.JavaDtoStyle
import ru.curs.hurdygurdy.KotlinCodegen
import ru.curs.hurdygurdy.Role
import java.nio.file.Files

@CacheableTask
abstract class GenerateTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spec: RegularFileProperty

    /**
     * Files transitively referenced from the spec via `$ref: "<file>#/..."`,
     * declared as inputs so that editing a referenced file re-runs the task.
     * Wired by [HurdyGurdyPlugin] from [ru.curs.hurdygurdy.ExternalRefs].
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val referencedSpecs: ConfigurableFileCollection

    @get:Input abstract val rootPackage: Property<String>
    @get:Input abstract val framework: Property<Framework>
    @get:Input abstract val language: Property<Language>
    @get:Input abstract val javaDtoStyle: Property<JavaDtoStyle>
    @get:Input abstract val generate: SetProperty<Role>
    @get:Input abstract val generateResponseParameter: Property<Boolean>
    @get:Input abstract val forceSnakeCaseForProperties: Property<Boolean>
    @get:Input abstract val generateAliasAsModel: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val outDir = outputDir.get().asFile
        if (outDir.exists()) outDir.deleteRecursively()
        Files.createDirectories(outDir.toPath())

        val params = GeneratorParams.rootPackage(rootPackage.get())
            .generateResponseParameter(generateResponseParameter.get())
            .forceSnakeCaseForProperties(forceSnakeCaseForProperties.get())
            .generateAliasAsModel(generateAliasAsModel.get())
            .framework(framework.get())
            .javaDtoStyle(javaDtoStyle.get())
            .generate(generate.get())

        val codegen: Codegen<*> = when (language.get()) {
            Language.JAVA -> JavaCodegen(params)
            Language.KOTLIN -> KotlinCodegen(params)
        }
        codegen.generate(spec.get().asFile.toPath(), outDir.toPath())
    }
}
