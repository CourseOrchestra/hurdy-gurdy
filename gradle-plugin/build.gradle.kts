plugins {
    kotlin("jvm") version "2.4.0"
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "ru.curs"

// Compile against Java 17 (matching the core artifact's maven.compiler.release)
// regardless of the JDK running Gradle, so the published plugin loads on any
// consumer Gradle running on JDK 17+.
kotlin {
    jvmToolchain(17)
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("ru.curs:hurdy-gurdy:${property("hurdyGurdyCoreVersion")}")
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
