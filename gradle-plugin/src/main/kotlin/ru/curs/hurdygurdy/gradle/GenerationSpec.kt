package ru.curs.hurdygurdy.gradle

import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.JavaDtoStyle
import ru.curs.hurdygurdy.Role
import javax.inject.Inject

abstract class GenerationSpec @Inject constructor(private val specName: String) : Named {

    abstract val spec: RegularFileProperty
    abstract val rootPackage: Property<String>
    abstract val framework: Property<Framework>
    abstract val language: Property<Language>
    abstract val javaDtoStyle: Property<JavaDtoStyle>
    abstract val generate: SetProperty<Role>
    abstract val generateResponseParameter: Property<Boolean>
    abstract val forceSnakeCaseForProperties: Property<Boolean>
    abstract val generateAliasAsModel: Property<Boolean>
    abstract val outputDir: DirectoryProperty

    override fun getName(): String = specName
}
