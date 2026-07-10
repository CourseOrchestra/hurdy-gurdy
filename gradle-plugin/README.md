# hurdy-gurdy-gradle-plugin

Gradle plugin wrapper around the hurdy-gurdy OpenAPI code generator.

## Building locally

The plugin depends on the released core artifact. Install the core to Maven Local first:

    mvn -q -DskipTests install        # from the repo root
    cd gradle-plugin && ./gradlew build

## Releasing

1. Release and publish `ru.curs:hurdy-gurdy` (the Maven artifact) to Maven Central.
2. Set `hurdyGurdyCoreVersion` and `version` in `gradle.properties` to the released version.
3. Publish the plugin:

       ./gradlew publishPlugins

   Requires `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` (Gradle Plugin Portal credentials).
