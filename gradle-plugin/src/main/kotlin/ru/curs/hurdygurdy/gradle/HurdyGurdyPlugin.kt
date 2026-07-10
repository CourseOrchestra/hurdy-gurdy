package ru.curs.hurdygurdy.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.Role

class HurdyGurdyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val specs = project.container(GenerationSpec::class.java)
        project.extensions.add("hurdyGurdy", specs)

        specs.all { spec ->
            spec.framework.convention(Framework.SPRING)
            spec.language.convention(Language.JAVA)
            spec.generate.convention(setOf(Role.CONTROLLER))
            spec.generateResponseParameter.convention(false)
            spec.forceSnakeCaseForProperties.convention(true)
            spec.outputDir.convention(
                project.layout.buildDirectory.dir("generated-sources/hurdy-gurdy/${spec.name}")
            )

            val taskName = "generate" + spec.name.replaceFirstChar { it.uppercase() }
            project.tasks.register(taskName, GenerateTask::class.java) { task ->
                task.group = "hurdy-gurdy"
                task.description = "Generates code from the '${spec.name}' OpenAPI spec"
                task.spec.set(spec.spec)
                task.rootPackage.set(spec.rootPackage)
                task.framework.set(spec.framework)
                task.language.set(spec.language)
                task.generate.set(spec.generate)
                task.generateResponseParameter.set(spec.generateResponseParameter)
                task.forceSnakeCaseForProperties.set(spec.forceSnakeCaseForProperties)
                task.outputDir.set(spec.outputDir)
            }
        }
    }
}
