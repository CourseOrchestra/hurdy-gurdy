package ru.curs.hurdygurdy;

import com.squareup.javapoet.TypeSpec;
import org.approvaltests.Approvals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class CodegenTest {
    private JavaCodegen codegen = new JavaCodegen(
            GeneratorParams.rootPackage("com.example").generateResponseParameter(true));
    Path result;

    @BeforeEach
    void setUp() throws IOException {
        result = Files.createTempDirectory("codegen");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(result)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void generateSample1() throws IOException {
        codegen.generate(Path.of("src/test/resources/sample1.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void generateSample2() throws IOException {
        codegen.generate(Path.of("src/test/resources/sample2.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void doNotGenerateResponseParameter() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .generateApiInterface(true));
        codegen.generate(Path.of("src/test/resources/sample1.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void generateCommonParameters() throws IOException {
        codegen.generate(Path.of("src/test/resources/commonparam.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void generateMultipart() throws IOException {
        codegen.generate(Path.of("src/test/resources/multipart.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void generateFromSpecs() throws IOException {
        codegen.addTypeSpec(ClassCategory.DTO, TypeSpec.interfaceBuilder("Intf1").build());
        codegen.addTypeSpec(ClassCategory.DTO, TypeSpec.interfaceBuilder("Intf2").build());
        codegen.addTypeSpec(ClassCategory.CONTROLLER, TypeSpec.interfaceBuilder("Intf3").build());
        codegen.generate(result);
        Approvals.verify(getContent(result));
    }

    @Test
    void paramsOverriding() throws IOException {
        codegen.generate(Path.of("src/test/resources/twoparams.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void camelCase() throws IOException {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/camelcase.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @Test
    void dictionarySupport() throws IOException {
        codegen.generate(Path.of("src/test/resources/dictionary.yaml"), result);
        Approvals.verify(getContent(result));
    }

    String getContent(Path path) throws IOException {
        return Files.walk(path)
                .sorted()
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