package ru.curs.hurdygurdy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.Role

@CacheableTask
abstract class GenerateTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spec: RegularFileProperty

    @get:Input abstract val rootPackage: Property<String>
    @get:Input abstract val framework: Property<Framework>
    @get:Input abstract val language: Property<Language>
    @get:Input abstract val generate: SetProperty<Role>
    @get:Input abstract val generateResponseParameter: Property<Boolean>
    @get:Input abstract val forceSnakeCaseForProperties: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        // implemented in Task 3
    }
}
