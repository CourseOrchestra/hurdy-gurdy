# hurdy-gurdy-gradle-plugin

Gradle plugin wrapper around the hurdy-gurdy OpenAPI code generator.

## Building locally

The plugin depends on the core artifact (`ru.curs:hurdy-gurdy`). For local
development against an unreleased core, seed it into an isolated Maven repo (so
the shared `~/.m2` is left untouched) and point Gradle at it:

    # from the repo root
    mvn -q -B -DskipTests package
    mvn -q -B install:install-file \
        -Dfile=target/hurdy-gurdy-2.11-SNAPSHOT.jar \
        -DpomFile=pom.xml \
        -Dmaven.repo.local="$PWD/.m2-core"
    cd gradle-plugin && ./gradlew build -Dmaven.repo.local="$PWD/../.m2-core"

`install:install-file` seeds only the jar + POM, skipping the `install`
lifecycle (no GPG signing). Once the core version you need is on Maven Central,
none of this is necessary — Gradle resolves it from there.

## Releasing

1. Release and publish `ru.curs:hurdy-gurdy` (the Maven artifact) to Maven Central.
2. Set `hurdyGurdyCoreVersion` and `version` in `gradle.properties` to the released version.
3. Publish the plugin:

       ./gradlew publishPlugins

   Requires `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` (Gradle Plugin Portal credentials).
