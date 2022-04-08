[![Actions Status: build](https://github.com/courseorchestra/hurdy-gurdy/workflows/build/badge.svg)](https://github.com/courseorchestra/hurdy-gurdy/actions?query=workflow%3A"build")

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/ru.curs/hurdy-gurdy/badge.svg)](https://maven-badges.herokuapp.com/maven-central/ru.curs/hurdy-gurdy)

# Hurdy-Gurdy

Generates client and server side Java/Kotlin code based on OpenAPI spec, using [swagger-parser](https://github.com/swagger-api/swagger-parser), [JavaPoet](https://github.com/square/javapoet) and [KotlinPoet](https://github.com/square/kotlinpoet).

## Usage example (as Maven plugin)
```xml
<plugin>
    <groupId>ru.curs</groupId>
    <artifactId>hurdy-gurdy</artifactId>
    <version>1.14</version>
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
val codegen = KotlinCodegen("com.example.project", true)
val yamlPath = project.layout.projectDirectory.asFile.toPath().resolve("src/main/openapi/api.yaml")
val resultPath = project.layout.buildDirectory.get().asFile.toPath().resolve("generated-sources")
Files.createDirectories(resultPath)
codegen.generate(yamlPath, resultPath)
```

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

This will procude the following in Kotlin:

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

