package ru.curs.clickmatters.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Codegen {

    private final String rootPackage;
    private OpenAPI openAPI;
    private Map<ClassCategory, List<TypeSpec>> typeSpecs = new EnumMap<>(ClassCategory.class);
    private final TypeDefiner typeDefiner;
    private final List<TypeSpecExtractor> typeSpecExtractors;


    public Codegen(String rootPackage, boolean generateResponseParameter) {
        this.rootPackage = rootPackage;
        this.typeDefiner = new TypeDefiner(rootPackage, this::addTypeSpec);
        typeSpecExtractors =  List.of(
                new DTOExtractor(typeDefiner), new APIExtractor(typeDefiner, generateResponseParameter));
    }

    private void parse(Path sourceFile) throws IOException {
        if (!Files.isReadable(sourceFile)) throw new IllegalArgumentException(
                String.format("File %s is not readable", sourceFile));
        ParseOptions parseOptions = new ParseOptions();
        SwaggerParseResult result = new OpenAPIParser()
                .readContents(Files.readString(sourceFile), null, parseOptions);
        openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalArgumentException(String.join(String.format("%n"), result.getMessages()));
        }
    }

    public void generate(Path sourceFile, Path resultDirectory) throws IOException {
        parse(sourceFile);

        if (!Files.isDirectory(resultDirectory)) throw new IllegalArgumentException(
                String.format("File %s is not a directory", resultDirectory));


        typeSpecExtractors.forEach(e -> e.extractTypeSpecs(openAPI, this::addTypeSpec));
        generate(resultDirectory);
    }


    void generate(Path resultDirectory) throws IOException {
        for (Map.Entry<ClassCategory, List<TypeSpec>> typeSpecsEntry : typeSpecs.entrySet()) {
            for (TypeSpec typeSpec : typeSpecsEntry.getValue()) {
                JavaFile javaFile = JavaFile.builder(String.join(".", rootPackage,
                        typeSpecsEntry.getKey().getPackageName()),
                        typeSpec).build();
                javaFile.writeTo(resultDirectory);
            }
        }
    }

    public void addTypeSpec(ClassCategory classCategory, TypeSpec typeSpec) {
        List<TypeSpec> specList = this.typeSpecs.computeIfAbsent(classCategory, n -> new ArrayList<>());
        specList.add(typeSpec);
    }
}
