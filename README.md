[![Actions Status: build](https://github.com/courseorchestra/hurdy-gurdy/workflows/build/badge.svg)](https://github.com/courseorchestra/hurdy-gurdy/actions?query=workflow%3A"build")

[![Maven Central](https://img.shields.io/maven-central/v/ru.curs/hurdy-gurdy)](https://central.sonatype.com/artifact/ru.curs/hurdy-gurdy)



# Hurdy-Gurdy

Generates client and server side Java/Kotlin code based on OpenAPI spec, using [swagger-parser](https://github.com/swagger-api/swagger-parser), [JavaPoet](https://github.com/square/javapoet) and [KotlinPoet](https://github.com/square/kotlinpoet).

## Usage example (as Maven plugin)
```xml
<plugin>
    <groupId>ru.curs</groupId>
    <artifactId>hurdy-gurdy</artifactId>
    <version>2.10</version>
    <configuration>
        <!--Root package for generated code-->
        <rootPackage>com.example.project</rootPackage>
        <spec>${basedir}/src/main/openapi/api.yaml</spec>
        <!--Set to true if you want to have HttpServletResponse response 
        parameter in Controller interface: good for server-side code.
        Set to false (default) if you don't need one 
        (good for client-side)-->
        <generateResponseParameter>true</generateResponseParameter>
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

## Usage example (in Kotlin code, e.g. Gradle's buildSrc)

```kotlin
import ru.curs.hurdygurdy.KotlinCodegen
import ru.curs.hurdygurdy.GeneratorParams

val codegen = KotlinCodegen(GeneratorParams.rootPackage("com.example.project"))
val yamlPath = project.layout.projectDirectory.asFile.toPath().resolve("src/main/openapi/api.yaml")
val resultPath = project.layout.buildDirectory.get().asFile.toPath().resolve("generated-sources")
Files.createDirectories(resultPath)
codegen.generate(yamlPath, resultPath)
```

## Configuration parameters

| Parameter name | Type | Default value | Description |
|--------------------------|------|---------------|-------------|
|`rootPackage`|String | | Sets the root package for all the generated classes. `Controller` and `Api` interfaces will be generated in `controller` subpackage, and all the DTOs will be generated in `dto` subpackage.
|`generateResponseParameter`|boolean|false|Set to true if you need to have `HttpServletResponse` parameter in each generated `Controller` method. You might need this in order to return specific HTTP status codes. When used together with the operation-level extension `x-include-request: true`, the generated method will also include `jakarta.servlet.http.HttpServletRequest request` parameter.
|`generateApiInterface`|boolean|false|Set to true if you need to generate an interface called `Api` besides `Controller`. `Api` methods do not have `HttpServletResponse` parameter.
|`forceSnakeCaseForProperties`|boolean|true|By default, hurdy-gurdy expects all the properties of DTO classes to be defined in _snake_case_ in the specification. It converts these names to _camelCase_ for generated classes and sets Jackson's `SnakeCaseStrategy` so that they will still be _snake_case_ in JSON representation. If you don't want this (e. g. if you want your properties to be defined in _camelCase_ everywhere) you can turn off this function via this parameter. 
|`framework`|String (`spring`\|`quarkus`)|`spring`|Selects the web framework whose annotations are emitted on the generated `Controller`/`Api` interfaces. `spring` (default) emits Spring MVC annotations (`@GetMapping`, `@PathVariable`, …). `quarkus` emits Jakarta REST / Quarkus annotations (`@GET` + `@Path`, `@PathParam`, `@QueryParam`, `@HeaderParam`, `@RestForm` for multipart). Value is case-insensitive.|

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
generated method returns `jakarta.ws.rs.core.Response` (instead of the DTO), and
a Javadoc/KDoc `@return` line documents the entity type the `Response` is
expected to carry. When `generateResponseParameter` is true and the
`x-include-request: true` operation extension is present, the generated method
also gains a `@Context jakarta.ws.rs.container.ContainerRequestContext
requestContext` parameter (the Quarkus analog of the Spring `HttpServletRequest`
behavior).

The generated Quarkus code requires `jakarta.ws.rs-api` on the consuming project's
classpath. For multipart endpoints, it also requires `org.jboss.resteasy.reactive.RestForm`
and `org.jboss.resteasy.reactive.multipart.FileUpload` — both provided by the
Quarkus REST extension.

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
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Car extends Vehicle {
    private String carProperty;
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

