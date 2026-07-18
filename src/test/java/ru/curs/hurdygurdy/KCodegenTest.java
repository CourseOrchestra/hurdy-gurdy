package ru.curs.hurdygurdy;

import com.squareup.kotlinpoet.TypeSpec;
import org.approvaltests.Approvals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class KCodegenTest {
    private KotlinCodegen codegen = new KotlinCodegen(
            GeneratorParams.rootPackage("com.example").generateResponseParameter(true));
    @TempDir
    Path result;

    @Test
    void generateSample1() throws IOException {
        codegen.generate(Path.of("src/test/resources/sample1.yaml"), result);
        // Snapshot only: sample1.yaml references types from externalfile.yaml that
        // are not generated here, so the output cannot be compiled in isolation.
        Approvals.verify(getContent(result));
    }

    @Test
    void generateSample2() throws IOException {
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void generateSample3() throws IOException {
        codegen.generate(Path.of("src/test/resources/sample3.yaml"), result);
        verify(result);
    }

    @Test
    void doNotGenerateResponseParameter() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams
                .rootPackage("com.example")
                .generateResponseParameter(false)
                .generate(Role.CONTROLLER, Role.API));
        codegen.generate(Path.of("src/test/resources/sample1.yaml"), result);
        // Snapshot only: see generateSample1 — references external types.
        Approvals.verify(getContent(result));
    }

    @Test
    void arrayAliasInlined() throws IOException {
        // Reproducer from OpenAPITools/openapi-generator#23988: ItemArray is an
        // alias for an array of Item. By default the alias is inlined at every
        // point of use (List<Item>) and no ItemArray class is generated.
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/issue23988.yaml"), result);
        verify(result);
    }

    @Test
    void arrayAliasAsModel() throws IOException {
        // Same spec with generateAliasAsModel (mirroring openapi-generator's
        // parameter): the alias becomes a model of its own,
        // class ItemArray : ArrayList<Item>().
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false)
                .generateAliasAsModel(true));
        codegen.generate(Path.of("src/test/resources/issue23988.yaml"), result);
        verify(result);
    }

    @Test
    void generateCommonParameters() throws IOException {
        codegen.generate(Path.of("src/test/resources/commonparam.yaml"), result);
        verify(result);
    }

    @Test
    void generateFromSpecs() throws IOException {
        codegen.addTypeSpec(ClassCategory.DTO, TypeSpec.interfaceBuilder("Intf1").build());
        codegen.addTypeSpec(ClassCategory.DTO, TypeSpec.interfaceBuilder("Intf2").build());
        codegen.addTypeSpec(ClassCategory.CONTROLLER, TypeSpec.interfaceBuilder("Intf3").build());
        codegen.generate(result);
        verify(result);
    }

    @Test
    void generateMultipart() throws IOException {
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        verify(result);
    }

    @Test
    void paramsOverriding() throws IOException {
        codegen.generate(Path.of("src/test/resources/twoparams.yaml"), result);
        verify(result);
    }

    @Test
    void camelCase() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/camelcase.yaml"), result);
        verify(result);
    }

    @Test
    void nullableParent() throws IOException {
        codegen.generate(Path.of("src/test/resources/nullableparent.yaml"), result);
        verify(result);
    }

    @Test
    void requiredNullable() throws IOException {
        codegen.generate(Path.of("src/test/resources/required_nullable.yaml"), result);
        verify(result);
    }

    @Test
    void requiredNullableObjects() throws IOException {
        codegen.generate(Path.of("src/test/resources/required_nullable_objects.yaml"), result);
        verify(result);
    }

    @Test
    void dictionarySupport() throws IOException {
        codegen.generate(Path.of("src/test/resources/dictionary.yaml"), result);
        verify(result);
    }

    @Test
    void oneOfSupport() throws IOException {
        codegen.generate(Path.of("src/test/resources/oneofsupport.yaml"), result);
        verify(result);
    }

    @Test
    void anyOfSupport() throws IOException {
        codegen.generate(Path.of("src/test/resources/anyofsupport.yaml"), result);
        verify(result);
    }

    @Test
    void noOwnTypes() throws IOException {
        codegen.generate(Path.of("src/test/resources/externaltype.yaml"), result);
        // Snapshot only: this spec deliberately references external, un-generated
        // types, so the output cannot be compiled in isolation.
        Approvals.verify(getContent(result));
    }

    @Test
    void deepInheritance() throws IOException {
        codegen.generate(Path.of("src/test/resources/deep_inheritance.yaml"), result);
        verify(result);
    }

    @Test
    void browseruse() throws IOException {
        codegen.generate(Path.of("src/test/resources/browseruse.json"), result);
        verify(result);
    }

    @Test
    void quarkusGenerateSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusDoNotGenerateResponseParameter() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/commonparam.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusMultipart() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        verify(result);
    }

    @Test
    void inheritedDiscriminatorProperty() throws IOException {
        codegen.generate(Path.of("src/test/resources/matchconfig.yaml"), result);
        verify(result);
    }

    @Test
    void inheritedRootProperty() throws IOException {
        // The maintainer's example from PR #233: a root-class property (`description`)
        // inherited by children, including an otherwise-empty leaf (`C`). Before the
        // fix `C` was generated as an `object` and `description` was not overridden,
        // producing non-compiling code.
        codegen.generate(Path.of("src/test/resources/pr233_inheritance.yaml"), result);
        verify(result);
    }

    @Test
    void mappingLessDiscriminator() throws IOException {
        // polyrecord.yaml's `Animal` is a discriminator base WITHOUT an explicit
        // `discriminator.mapping`. The generator must derive @JsonSubTypes from the
        // schemas whose allOf references Animal (Cat, Dog), so Jackson can resolve
        // the subtype name on deserialize. See effectiveSubclassMapping.
        codegen.generate(Path.of("src/test/resources/polyrecord.yaml"), result);
        verify(result);
    }

    @Test
    void childlessDiscriminatorFallsBackToInstantiable() throws IOException {
        // A discriminator base with no subtypes (no allOf children, no explicit
        // mapping) would otherwise become an uninstantiable empty `sealed class`.
        // It must fall back to a normal `data class` (keeping @JsonTypeInfo) so it
        // can be created/deserialized, while a discriminator base WITH children
        // (Animal) stays sealed. The snapshot locks both shapes.
        codegen.generate(Path.of("src/test/resources/childlessDiscriminator.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(Framework.class)
    void youtrackOpenapiCompiles(Framework framework) throws IOException {
        // Real-world regression: the YouTrack OpenAPI spec has deep, property-
        // redeclaring inheritance chains (e.g. ActivityItem -> MultiValueActivityItem
        // -> WorkItemTypeActivityItem) that previously produced duplicate constructor
        // properties and non-compiling code. Its properties are camelCase, so the
        // snake-case check is disabled. Compile-only: no snapshot for 200+ files.
        // Every framework and every role is covered, so the whole generation
        // surface is exercised on a real spec.
        KotlinCodegen kc = new KotlinCodegen(GeneratorParams
                .rootPackage("org.youtrack")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(false)
                .framework(framework)
                .generate(EnumSet.allOf(Role.class)));
        kc.generate(Path.of("src/test/resources/youtrack_openapi.json"), result);
        GeneratedCodeCompiler.assertKotlinCompiles(result);
    }

    @Test
    void springClientSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.SPRING).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientMultipart() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusServerAndClientSample2() throws IOException {
        // Both roles in a single run: XxxController (server resource) and
        // XxxClient (@RegisterRestClient) side by side, sharing the DTOs.
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS)
                .generate(Role.CONTROLLER, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void springAllRolesSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .generate(Role.CONTROLLER, Role.API, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }


    /**
     * Verifies the generated output against its snapshot and additionally
     * verifies that the generated Kotlin code compiles.
     */
    void verify(Path path) throws IOException {
        Approvals.verify(getContent(path));
        GeneratedCodeCompiler.assertKotlinCompiles(path);
    }

    String getContent(Path path) throws IOException {
        return Files.walk(path)
                .sorted(Comparator.comparing(Path::toString))
                .flatMap(p -> Stream.concat(
                        Stream.of(
                                String.format("---%n"),
                                String.format("%s%n", p.toString()
                                        .replaceAll(String.format("\\%s", File.separator), "/")
                                        .substring(result.toString().length()))
                        ),
                        readFile(p))
                ).collect(Collectors.joining());
    }

    Stream<String> readFile(Path path) {
        String result;
        if (Files.isReadable(path)) {
            try {
                result = Files.readString(path);
            } catch (IOException e) {
                result = null;
            }
            return Stream.ofNullable(result);
        } else {
            return Stream.empty();
        }
    }
}
