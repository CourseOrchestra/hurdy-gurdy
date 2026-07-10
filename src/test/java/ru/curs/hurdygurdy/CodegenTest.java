package ru.curs.hurdygurdy;

import com.squareup.javapoet.TypeSpec;
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

class CodegenTest {
    private JavaCodegen codegen = new JavaCodegen(
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
    void doNotGenerateResponseParameter() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .generate(Role.CONTROLLER, Role.API));
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
    void generateMultipart() throws IOException {
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
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
    void paramsOverriding() throws IOException {
        codegen.generate(Path.of("src/test/resources/twoparams.yaml"), result);
        verify(result);
    }

    @Test
    void camelCase() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/camelcase.yaml"), result);
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
        // types (e.g. com.example.collector.api.dto.LicenseResponse), so the output
        // cannot be compiled in isolation.
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
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusDoNotGenerateResponseParameter() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/commonparam.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusMultipart() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS));
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        verify(result);
    }

    @Test
    void springClientSample2() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.SPRING).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientSample2() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusClientMultipart() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS).generate(Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        verify(result);
    }

    @Test
    void quarkusServerAndClientSample2() throws IOException {
        // Both roles in a single run: XxxController (server resource) and
        // XxxClient (@RegisterRestClient) side by side, sharing the DTOs.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .framework(Framework.QUARKUS)
                .generate(Role.CONTROLLER, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void springAllRolesSample2() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .generate(Role.CONTROLLER, Role.API, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
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
        // inherited by children, including an otherwise-empty leaf (`C`).
        codegen.generate(Path.of("src/test/resources/pr233_inheritance.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(Framework.class)
    void youtrackOpenapiCompiles(Framework framework) throws IOException {
        // Real-world regression: the YouTrack OpenAPI spec redeclares inherited
        // properties (often with a narrower type) across deep allOf chains, which
        // previously made Lombok emit clashing/uncompilable getters and setters, and
        // has a multipart parameter literally named "files[0]" that is not a legal
        // identifier. Properties are camelCase, so the snake-case check is disabled.
        // Compile-only: no snapshot for 200+ files. Every framework and every role
        // is covered, so the whole generation surface is exercised on a real spec.
        JavaCodegen jc = new JavaCodegen(GeneratorParams
                .rootPackage("org.youtrack")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(false)
                .framework(framework)
                .generate(EnumSet.allOf(Role.class)));
        jc.generate(Path.of("src/test/resources/youtrack_openapi.json"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    /**
     * Verifies the generated output against its snapshot and additionally
     * verifies that the generated Java code compiles.
     */
    void verify(Path path) throws IOException {
        Approvals.verify(getContent(path));
        GeneratedCodeCompiler.assertJavaCompiles(path);
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