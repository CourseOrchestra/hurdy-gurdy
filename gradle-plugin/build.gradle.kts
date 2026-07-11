import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Node

plugins {
    kotlin("jvm") version "2.4.0"
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

// Single source of truth for the version: the top-level <version> of the core
// Maven build (../pom.xml). The plugin's own version and the core artifact it
// depends on are always released in lock-step, so we derive both from there
// rather than duplicating the value in gradle.properties.
val hurdyGurdyVersion: String = run {
    val pom = layout.projectDirectory.file("../pom.xml").asFile
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(pom)
    val children = doc.documentElement.childNodes
    (0 until children.length)
        .map { children.item(it) }
        .firstOrNull { it.nodeType == Node.ELEMENT_NODE && it.nodeName == "version" }
        ?.textContent?.trim()
        ?: throw GradleException("Could not find the top-level <version> in ${pom.path}")
}

group = "ru.curs"
version = hurdyGurdyVersion

// Emit Java 17 bytecode (matching the core artifact's maven.compiler.release)
// regardless of the JDK running Gradle, so the published plugin loads on any
// consumer Gradle running on JDK 17+. We target 17 rather than pinning a
// toolchain so the build runs on a single JDK (the core Maven build requires
// JDK 21+ to build, while both target Java 17 bytecode).
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("ru.curs:hurdy-gurdy:$hurdyGurdyVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
}

gradlePlugin {
    website = "https://github.com/courseorchestra/hurdy-gurdy"
    vcsUrl = "https://github.com/courseorchestra/hurdy-gurdy"
    plugins {
        register("hurdyGurdy") {
            id = "ru.curs.hurdy-gurdy"
            displayName = "Hurdy-Gurdy OpenAPI codegen"
            description = "Generates client/server Java/Kotlin code from an OpenAPI spec."
            tags = listOf("openapi", "codegen", "spring", "quarkus", "kotlin")
            implementationClass = "ru.curs.hurdygurdy.gradle.HurdyGurdyPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
