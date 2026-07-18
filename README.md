<p align="center"><img src="docs/logo.png" alt="Hurdy-Gurdy" width="220"></p>

[![Actions Status: build](https://github.com/courseorchestra/hurdy-gurdy/workflows/build/badge.svg)](https://github.com/courseorchestra/hurdy-gurdy/actions?query=workflow%3A"build")

[![Maven Central](https://img.shields.io/maven-central/v/ru.curs/hurdy-gurdy)](https://central.sonatype.com/artifact/ru.curs/hurdy-gurdy)



# Hurdy-Gurdy

Generates client and server side Java/Kotlin code based on OpenAPI spec, using [swagger-parser](https://github.com/swagger-api/swagger-parser), [JavaPoet](https://github.com/palantir/javapoet) and [KotlinPoet](https://github.com/square/kotlinpoet).

## Usage examples

* Java or Kotlin? 
* Spring or Quarkus? 
* Maven, Gradle, or plain CLI?  
* Lombok, POJO or Java records for DTOs?

[Fill this form](https://courseorchestra.github.io/hurdy-gurdy/) and copy the config for your build tool.

### Maven plugin

```xml
<plugin>
    <groupId>ru.curs</groupId>
    <artifactId>hurdy-gurdy</artifactId>
    <version>3.1</version>
    <configuration>
        <!--Root package for generated code-->
        <rootPackage>com.example.project</rootPackage>
        <spec>${basedir}/src/main/openapi/api.yaml</spec>
        <!--Optional: where generated sources are written
        (default target/generated-sources/openapi)-->
        <outputDirectory>${project.build.directory}/generated-sources/openapi</outputDirectory>
        <!--Set to true if you want to have HttpServletResponse response 
        parameter in Controller interface: good for server-side code.
        Set to false (default) if you don't need one 
        (good for client-side)-->
        <generateResponseParameter>true</generateResponseParameter>
        <!--Optional: target web framework (spring|quarkus, default spring)
        and which interfaces to generate (any subset of controller,api,client;
        default controller) — see "Generated interfaces" below-->
        <framework>spring</framework>
        <generate>controller,client</generate>
        <!--Optional: Java DTO style (lombok|pojo|records, default lombok);
        Java only — see "Java DTO styles" below-->
        <javaDtoStyle>lombok</javaDtoStyle>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>gen-server</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The `gen-server` goal skips regeneration when nothing the generated sources
depend on has changed since the last run: the spec file and every file it
(transitively) references via `$ref: "<file>#/..."`, the effective plugin
configuration, and the plugin version itself. This is tracked by marker files under `target/hurdy-gurdy/`.

For several specs in one build, declare one `<execution>` per spec, each with
its own `<configuration>` (including a distinct `<outputDirectory>`):

```xml
<executions>
    <execution>
        <id>gen-petstore</id>
        <goals><goal>gen-server</goal></goals>
        <configuration>
            <spec>${basedir}/src/main/openapi/petstore.yaml</spec>
            <rootPackage>com.example.petstore</rootPackage>
        </configuration>
    </execution>
    <execution>
        <id>gen-billing</id>
        <goals><goal>gen-server</goal></goals>
        <configuration>
            <spec>${basedir}/src/main/openapi/billing.yaml</spec>
            <rootPackage>com.example.billing</rootPackage>
            <outputDirectory>${project.build.directory}/generated-sources/billing</outputDirectory>
        </configuration>
    </execution>
</executions>
```

### Gradle plugin

```kotlin
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.Role
import ru.curs.hurdygurdy.gradle.Language

plugins {
    java
    id("ru.curs.hurdy-gurdy") version "3.1"
}

hurdyGurdy {
    "petstore" {
        spec = layout.projectDirectory.file("src/main/openapi/api.yaml")
        rootPackage = "com.example.project"
        framework = Framework.SPRING            // default SPRING
        language = Language.JAVA                // default JAVA
        generate = setOf(Role.CONTROLLER)       // default [CONTROLLER]
        generateResponseParameter = true        // default false
        forceSnakeCaseForProperties = true      // default true
    }
}
```

Each named block registers a `generate<Name>` task (e.g. `generatePetstore`) whose
output dir is added to the `main` source set, so `compileJava`/`compileKotlin`
depend on it automatically. The task is cacheable: unchanged inputs — the spec,
every file it (transitively) references via `$ref: "<file>#/..."`, and the
configuration — keep both generation and dependent compilation `UP-TO-DATE`.

For Kotlin output, set `language = Language.KOTLIN` and apply the Kotlin JVM plugin.

### Direct API usage from Kotlin code

```kotlin
import ru.curs.hurdygurdy.Framework
import ru.curs.hurdygurdy.GeneratorParams
import ru.curs.hurdygurdy.KotlinCodegen
import ru.curs.hurdygurdy.Role

val codegen = KotlinCodegen(
    GeneratorParams.rootPackage("com.example.project")
        // optional: spring is the default
        .framework(Framework.QUARKUS)
        // optional: controller alone is the default;
        // a Kotlin collection works too: .generate(listOf(Role.CONTROLLER, Role.CLIENT))
        .generate(Role.CONTROLLER, Role.CLIENT)
)
val yamlPath = project.layout.projectDirectory.asFile.toPath().resolve("src/main/openapi/api.yaml")
val resultPath = project.layout.buildDirectory.get().asFile.toPath().resolve("generated-sources")
Files.createDirectories(resultPath)
codegen.generate(yamlPath, resultPath)
```

### CLI

Build the executable fat jar:

    mvn -DskipTests package
    java -jar target/hurdy-gurdy-<version>-cli.jar \
        --spec src/main/openapi/api.yaml \
        --root-package com.example.project \
        --output generated-sources \
        --framework spring --generate controller,client

Options: `--language java|kotlin`, `--framework spring|quarkus`,
`--generate` (subset of `controller,api,client`), `--response-parameter`,
`--force-snake-case` (negate with `--no-...`). Run with `--help` for the full list.

Optionally build a native binary (requires GraalVM):

    mvn -Pnative -DskipTests package
    ./target/hurdy-gurdy --spec ... --root-package ... --output ...


## Configuration parameters

| Parameter name | Type | Default value | Description |
|--------------------------|------|---------------|-------------|
|`rootPackage`|String | | Sets the root package for all the generated classes. `Controller`, `Api` and `Client` interfaces will be generated in `controller` subpackage, and all the DTOs will be generated in `dto` subpackage.
|`generateResponseParameter`|boolean|false|Set to true if you need access to the raw HTTP response. For `controller`: adds an `HttpServletResponse` parameter to each method (Spring) or makes methods return `jakarta.ws.rs.core.Response` (Quarkus) — useful for returning specific HTTP status codes; together with the operation-level extension `x-include-request: true`, the method also receives the request. For `client`: methods return the HTTP envelope (`ResponseEntity<T>` for Spring, `Response` for Quarkus). Never affects `api` interfaces.
|`generateApiInterface`|boolean|false|**Deprecated** — equivalent to adding `api` to `generate` (see below). Kept for backwards compatibility.
|`forceSnakeCaseForProperties`|boolean|true|By default, hurdy-gurdy expects all the properties of DTO classes to be defined in _snake_case_ in the specification. It converts these names to _camelCase_ for generated classes and sets Jackson's `SnakeCaseStrategy` so that they will still be _snake_case_ in JSON representation. If you don't want this (e. g. if you want your properties to be defined in _camelCase_ everywhere) you can turn off this function via this parameter. 
|`framework`|String (`spring`\|`quarkus`)|`spring`|Selects the web framework whose annotations are emitted on the generated interfaces. `spring` (default) emits Spring MVC annotations (`@GetMapping`, `@PathVariable`, …). `quarkus` emits Jakarta REST / Quarkus annotations (`@GET` + `@Path`, `@PathParam`, `@QueryParam`, `@HeaderParam`, `@RestForm` for multipart). Value is case-insensitive.|
|`generate`|comma-separated subset of `controller`, `api`, `client`|`controller`|Selects which interfaces to generate — any combination in a single run, e.g. `<generate>controller,client</generate>`. See [Generated interfaces](#generated-interfaces-generate). Case-insensitive.|
|`javaDtoStyle`|String (`lombok`\|`pojo`\|`records`)|`lombok`|Shape of the generated **Java** DTOs. `lombok` (default) emits Lombok `@Data` classes (requires Lombok on the classpath). `pojo` emits plain classes with explicit getters/setters plus `equals`/`hashCode`/`toString` — no Lombok dependency. `records` emits Java records. Applies to Java only; Kotlin always generates `data class`es. See [Java DTO styles](#java-dto-styles-javadtostyle). Case-insensitive.|

## Generated interfaces (`generate`)

The `generate` parameter selects which interfaces are emitted for the API paths — any
subset of `controller`, `api` and `client`, in a single run, all in the `controller`
subpackage and sharing the same DTOs. Combined with `framework`, this gives six
possible artifacts:

| `generate` value | `framework=spring` | `framework=quarkus` |
|---|---|---|
| `controller` | `XxxController` — server interface to implement: `@GetMapping`, …; optional `HttpServletResponse` parameter | `XxxController` — Jakarta REST resource interface to implement: `@GET` + `@Path`, …; optional `Response` return type |
| `api` | `XxxApi` — pure contract: same Spring MVC annotations, no response-related artifacts. Directly consumable by [Spring Cloud OpenFeign](https://spring.io/projects/spring-cloud-openfeign) (its default `SpringMvcContract` parses `@GetMapping`/`@RequestMapping` on [OpenFeign](https://github.com/OpenFeign/feign) client interfaces), or as a typed contract for hand-written implementations, e.g. over [REST Assured](https://rest-assured.io/) in tests | `XxxApi` — pure contract: same Jakarta REST annotations, no response-related artifacts. Directly consumable by [MicroProfile REST Client](https://github.com/eclipse/microprofile-rest-client)'s `RestClientBuilder.newBuilder().baseUri(…).build(XxxApi.class)` (no `@RegisterRestClient` needed for the programmatic API), or as a typed contract for hand-written implementations |
| `client` | `XxxClient` — [Spring 6 HTTP Interface](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface): `@GetExchange`, …; create a proxy with `HttpServiceProxyFactory` | `XxxClient` — [MicroProfile / Quarkus REST Client](https://quarkus.io/guides/rest-client): `@RegisterRestClient` interface, inject it with `@RestClient` |

For example, a Quarkus service that also calls itself from tests (or a sibling service
consuming the same spec) can generate both sides at once:

```xml
<framework>quarkus</framework>
<generate>controller,client</generate>
```

`generateResponseParameter` applies per interface kind: it affects `controller`
(response parameter / `Response` return) and `client` (methods return the HTTP
envelope — `ResponseEntity<T>` for Spring, `Response` for Quarkus — so callers can
inspect status and headers), and never affects `api`. Server-only constructs
(`HttpServletResponse`, `@Context ContainerRequestContext`, `x-include-request`)
are omitted from `api` and `client` interfaces.

In code, use `GeneratorParams.rootPackage(...).generate(Role.CONTROLLER, Role.CLIENT)`.

## Java DTO styles (`javaDtoStyle`)

For Java output, `javaDtoStyle` selects the shape of the generated DTO classes.
It applies to Java only — Kotlin always generates `data class`es. The choice
never changes the JSON wire format: all three styles serialize and deserialize
the same JSON for the same specification.

| Style | What is generated | Notes |
|---|---|---|
| `lombok` (default) | Lombok `@Data` classes | Requires Lombok on the classpath. |
| `pojo` | Plain classes with explicit getters/setters plus `equals`/`hashCode`/`toString` | No Lombok dependency. Value semantics match `@Data` (own fields only). |
| `records` | Java records | Immutable; requires Java 17+. Record-style `name()` accessors (not `getName()`). |

In code:

```kotlin
import ru.curs.hurdygurdy.JavaDtoStyle

val codegen = JavaCodegen(
    GeneratorParams.rootPackage("com.example.project")
        .javaDtoStyle(JavaDtoStyle.RECORDS)
)
```

### How the styles model polymorphism and inheritance

`lombok` and `pojo` use ordinary Java classes and behave identically in shape:

- **`allOf` inheritance** → the subtype `extends` the base class.
- **`discriminator`** → the base carries `@JsonTypeInfo(use = NAME)` +
  `@JsonSubTypes`; subtypes `extend` it. When the schema declares no explicit
  `discriminator.mapping`, the `@JsonSubTypes` names are derived from the subtype
  schema names (the OpenAPI implicit convention), so deserialization works
  without a hand-written mapping.
- **`oneOf`** and a top-level **`anyOf`** of two or more `$ref`s → an interface
  carrying `@JsonTypeInfo(use = DEDUCTION)` + `@JsonSubTypes`; the member classes
  `implement` it.

`records` cannot use class inheritance (a Java record is `final` and cannot
`extend`), so is-a relationships are expressed through interfaces:

- **`discriminator`, `oneOf`, top-level `anyOf`** bases become `sealed interface`s
  that `permit` their subtypes; each concrete subtype is a `record` that
  `implements` the base (and any interface it participates in — a type can
  `implement` several).
- **`allOf`-inherited properties** are **flattened** into the subtype record's
  components (records inherit no fields). A plain `allOf` base (no
  `discriminator`/`oneOf`) stays its own record and is still instantiable; the
  subtype simply repeats its components — the JSON is identical.
- **Required** components are validated in a compact constructor
  (`Objects.requireNonNull`), so a missing required value fails fast.
- **`additionalProperties`** become a trailing `Map` component annotated
  `@JsonAnySetter`/`@JsonAnyGetter`.
- A `nullable` self-reference and self-referential (recursive) schemas are
  supported (a record may reference its own type as a component).

## Quarkus (Jakarta REST) output

Set `framework` to `quarkus` (Maven `<framework>quarkus</framework>`, or
`GeneratorParams.rootPackage(...).framework(Framework.QUARKUS)` in code) to
generate Jakarta REST interfaces instead of Spring MVC ones. DTO classes are
identical in both modes.

Annotation mapping:

| Concern | Spring | Quarkus (JAX-RS) |
|---------|--------|------------------|
| Interface | *(none)* | `@Path("")` |
| HTTP method | `@GetMapping(value, produces, consumes)` | `@GET` + `@Path(path)` + `@Produces` + `@Consumes` |
| Path parameter | `@PathVariable` | `@PathParam` |
| Query parameter | `@RequestParam` | `@QueryParam` (+ `@DefaultValue`) |
| Header parameter | `@RequestHeader` | `@HeaderParam` |
| Request body | `@RequestBody` | *(unannotated parameter)* |
| Multipart part | `@RequestPart` | `@RestForm` |

`@Produces` is emitted only when the operation defines a success (2xx) response
media type, and `@Consumes` only when the request body defines a media type.

`generateResponseParameter` has no servlet analog in Quarkus. When enabled, the
generated `Controller` method returns `jakarta.ws.rs.core.Response` (instead of the
DTO), and a Javadoc/KDoc `@return` line documents the entity type the `Response` is
expected to carry. When `generateResponseParameter` is true and the
`x-include-request: true` operation extension is present, the generated `Controller`
method also gains a `@Context jakarta.ws.rs.container.ContainerRequestContext
requestContext` parameter (the Quarkus analog of the Spring `HttpServletRequest`
behavior).

The generated Quarkus code requires `jakarta.ws.rs-api` on the consuming project's
classpath. For multipart endpoints, it also requires `org.jboss.resteasy.reactive.RestForm`
and `org.jboss.resteasy.reactive.multipart.FileUpload` — both provided by the
Quarkus REST extension.

## Client generation (`generate=client`)

With `client` in the `generate` set, the emitted `XxxClient` interfaces are meant to be
*called*, not implemented — the framework supplies the implementation.

- **Spring** (`framework=spring`): [Spring 6 HTTP Interface](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-http-interface).
  Methods carry `@GetExchange`/`@PostExchange`/`@PutExchange`/`@PatchExchange`/`@DeleteExchange`;
  parameters keep the same `@PathVariable`/`@RequestParam`/`@RequestHeader`/`@RequestBody`/`@RequestPart`
  annotations. Create a proxy with `HttpServiceProxyFactory`.
- **Quarkus** (`framework=quarkus`): the interface is additionally annotated `@RegisterRestClient`; inject it
  with `@RestClient`. No implementation is written. See the [Quarkus REST Client guide](https://quarkus.io/guides/rest-client).

`generateResponseParameter=true` makes client methods return the HTTP envelope so callers can inspect
status/headers: `ResponseEntity<T>` (Spring) / `jakarta.ws.rs.core.Response` (Quarkus). With
`generateResponseParameter=false` they return the deserialized DTO. Server-only constructs
(`HttpServletResponse`, `@Context ContainerRequestContext`, `x-include-request`) are omitted from client
interfaces.

## Inheritance hierarchy compatible with openapi-codegen

```yaml
components:
  schemas:
    #---------------------------------------------------------------------------
    # Abstract class with discriminator 'vehicle_type'
    #---------------------------------------------------------------------------
    'Vehicle':
      type: object
      nullable: false
      properties:
        'vehicle_type':
          type: string
      discriminator:
        propertyName: vehicle_type
        mapping:
          'CAR': '#/components/schemas/Car'
          'TRUCK': '#/components/schemas/Truck'
    #---------------------------------------------------------------------------
    # Concrete classes
    #---------------------------------------------------------------------------
    'Car':
      nullable: false
      allOf:
        - $ref: "#/components/schemas/Vehicle"
        - type: object
          properties:
            'car_property':
              type: string
    'Truck':
      nullable: false
      allOf:
        - $ref: "#/components/schemas/Vehicle"
        - type: object
          properties:
            'truck_property':
              type: string
```

This will produce the following in Java:
```java
//Vehicle.java
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "vehicle_type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Car.class, name = "CAR"),
        @JsonSubTypes.Type(value = Truck.class, name = "TRUCK")})
public class Vehicle {
}

//Car.java
@Data
@EqualsAndHashCode(callSuper = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Car extends Vehicle {
    private String carProperty;
}
```

The `@EqualsAndHashCode(callSuper = true)` is emitted on subtypes only (a class that
`extends` a generated parent), so inherited fields participate in `equals`/`hashCode`;
base and standalone classes keep a plain `@Data`.

With `javaDtoStyle=pojo` the same schema produces plain classes (no Lombok) with
explicit accessors and value methods:

```java
//Car.java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Car extends Vehicle {
    private String carProperty;

    public String getCarProperty() {
        return this.carProperty;
    }

    public void setCarProperty(String carProperty) {
        this.carProperty = carProperty;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Car that = (Car) o;
        return Objects.equals(carProperty, that.carProperty);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), carProperty);
    }

    @Override
    public String toString() {
        return "Car{" + "carProperty=" + carProperty + "}";
    }
}
```

With `javaDtoStyle=records` the discriminator base becomes a `sealed interface`
and each subtype a `record`, with inherited properties flattened into the
components and required ones null-checked in a compact constructor:

```java
//Vehicle.java
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "vehicle_type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Car.class, name = "CAR"),
    @JsonSubTypes.Type(value = Truck.class, name = "TRUCK")})
public sealed interface Vehicle permits Car, Truck {
}

//Car.java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Car(String carProperty) implements Vehicle {
}
```

This will produce the following in Kotlin:

```kotlin
//Vehicle.kt
@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "vehicle_type"
)
@JsonSubTypes(JsonSubTypes.Type(value = Car::class, name = "CAR"),
JsonSubTypes.Type(value = Truck::class, name = "TRUCK"))
public sealed class Vehicle()

//Car.kt
@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy::class)
public data class Car(
    public val carProperty: String? = null
) : Vehicle()
```

## Make DTO classes implement interfaces

You can use `x-extends` [extended property](https://swagger.io/docs/specification/openapi-extensions/) on schema element in order to make DTO implement given interface or interfaces: 

```yaml
components:
  schemas:
    MenuItemDTO:
      type: object
      nullable: false
      x-extends:
        - java.lang.Serializable
      title: MenuItemDTO
      properties:
        [....]
```
## References to external specifications
You can use references to external specification files if they are available on the same file system as the original one. However, hurdy-gurdy does not attempt to generate code for referenced specifications: we believe this should be done explicitly for every spec. Hurdy-gurdy just uses `x-package` extension property on the referenced specification in order to define the location of referenced DTOs.

For example, given the following spec fragment:

```yaml
  /api/v1/external:
    get:
      operationId: external
      responses:
        "200":
          description: external file
          content:
            text/csv:
              schema:
                $ref: 'externalfile.yaml#/components/schemas/DatabaseConnectionRequest'
```

The `externalfile.yaml` file should be located in the same folder and it should contain `x-package` property:

```yaml
openapi: 3.0.1
info:
paths:
x-package: com.example
```
Then code generator will suggest that `com.example.dto.DatabaseConnectionRequest` class exists on the classpath.

