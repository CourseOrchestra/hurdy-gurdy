package ru.curs.hurdygurdy;

import com.squareup.kotlinpoet.TypeSpec;
import org.approvaltests.Approvals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
                .generateApiInterface(true));
        codegen.generate(Path.of("src/test/resources/sample1.yaml"), result);
        // Snapshot only: see generateSample1 — references external types.
        Approvals.verify(getContent(result));
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
        // Snapshot only: compiling the generated @RestForm parameter would require
        // the resteasy-reactive artifact, which we deliberately do not depend on.
        Approvals.verify(getContent(result));
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
    void youtrackOpenapiCompiles() throws IOException {
        // Real-world regression: the YouTrack OpenAPI spec has deep, property-
        // redeclaring inheritance chains (e.g. ActivityItem -> MultiValueActivityItem
        // -> WorkItemTypeActivityItem) that previously produced duplicate constructor
        // properties and non-compiling code. Its properties are camelCase, so the
        // snake-case check is disabled. Compile-only: no snapshot for 200+ files.
        KotlinCodegen kc = new KotlinCodegen(GeneratorParams
                .rootPackage("org.youtrack")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(false));
        kc.generate(Path.of("src/test/resources/youtrack_openapi.json"), result);
        GeneratedCodeCompiler.assertKotlinCompiles(result);
    }

    @Test
    void springClientSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.SPRING).role(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientSample2() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).role(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientMultipart() throws IOException {
        codegen = new KotlinCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).role(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        // Snapshot only.
        Approvals.verify(getContent(result));
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
