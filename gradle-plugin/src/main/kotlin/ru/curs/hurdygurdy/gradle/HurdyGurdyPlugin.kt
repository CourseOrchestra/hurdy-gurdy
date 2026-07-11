package ru.curs.hurdygurdy.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSetContainer
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.Role

class HurdyGurdyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val specs = project.objects.domainObjectContainer(GenerationSpec::class.java)
        project.extensions.add(HurdyGurdyExtension::class.java, "hurdyGurdy", HurdyGurdyExtension(specs))

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
            val generateTask = project.tasks.register(taskName, GenerateTask::class.java) { task ->
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

            project.pluginManager.withPlugin("java") {
                val main = project.extensions.getByType(SourceSetContainer::class.java)
                    .getByName("main")
                project.afterEvaluate {
                    when (spec.language.get()) {
                        Language.JAVA -> main.java.srcDir(generateTask)
                        Language.KOTLIN -> {
                            if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
                                throw GradleException(
                                    "hurdyGurdy spec '${spec.name}' sets language = KOTLIN but the " +
                                    "Kotlin JVM plugin (org.jetbrains.kotlin.jvm) is not applied"
                                )
                            }
                            @Suppress("UNCHECKED_CAST")
                            val kotlin = main.extensions.getByName("kotlin") as SourceDirectorySet
                            kotlin.srcDir(generateTask)
                        }
                    }
                }
            }
        }
    }
}
