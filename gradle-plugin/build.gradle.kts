import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Node

plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
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
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
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

detekt {
    // Start from detekt's recommended defaults and layer this repo's overrides
    // (config/detekt/detekt.yml) on top, so the build fails on new findings
    // without us having to restate the entire default ruleset.
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

// Route `check` through the type-resolution-aware detekt tasks (detektMain /
// detektTest) rather than only the plain `detekt` task the plugin wires in by
// default: they enable the rules that need a compiled classpath, so `check`
// runs the fuller analysis. This is why they require the core ru.curs:hurdy-gurdy
// artifact to be resolvable (mavenLocal), same as compilation.
// Enforce the Apache 2.0 license header on every Kotlin/Java source, mirroring
// the checkstyle Header check in the core Maven build. There is a single header
// template for the whole repository (../header.txt); comparison is line-by-line
// with the trailing CR stripped, so it is agnostic to LF vs CRLF.
val licenseHeaderFile = layout.projectDirectory.file("../header.txt")
val licensedSources = fileTree("src") { include("**/*.kt", "**/*.java") }
val repoRootDir = projectDir
val verifyLicenseHeaders = tasks.register("verifyLicenseHeaders") {
    description = "Verifies every Kotlin/Java source starts with the Apache 2.0 license header."
    group = "verification"
    inputs.file(licenseHeaderFile)
    inputs.files(licensedSources)
    doLast {
        val expected = licenseHeaderFile.asFile.readLines().map { it.trimEnd('\r') }
        val offenders = licensedSources.files
            .filter { f -> f.readLines().take(expected.size).map { it.trimEnd('\r') } != expected }
            .map { it.relativeTo(repoRootDir).path }
            .sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Missing or malformed Apache 2.0 license header in:\n  " +
                    offenders.joinToString("\n  ")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("detektMain"), tasks.named("detektTest"), verifyLicenseHeaders)
}
