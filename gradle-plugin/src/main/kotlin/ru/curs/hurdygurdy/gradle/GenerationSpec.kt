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
