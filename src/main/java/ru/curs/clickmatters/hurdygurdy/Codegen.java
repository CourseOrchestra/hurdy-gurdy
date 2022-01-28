package ru.curs.clickmatters.hurdygurdy;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


public abstract class Codegen<T> {

    private final String rootPackage;
    private OpenAPI openAPI;
    private final Map<ClassCategory, List<T>> typeSpecs = new EnumMap<>(ClassCategory.class);
    private final List<TypeSpecExtractor<T>> typeSpecExtractors;


    public Codegen(String rootPackage, TypeProducersFactory<T> typeProducersFactory) {
        this.rootPackage = rootPackage;
        TypeDefiner<T> typeDefiner = typeProducersFactory.createTypeDefiner(this::addTypeSpec);
        typeSpecExtractors = typeProducersFactory.typeSpecExtractors(typeDefiner);
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
        for (Map.Entry<ClassCategory, List<T>> typeSpecsEntry : typeSpecs.entrySet()) {
            for (T typeSpec : typeSpecsEntry.getValue()) {
                final String packageName = String.join(".", rootPackage,
                        typeSpecsEntry.getKey().getPackageName());
                writeFile(resultDirectory, packageName, typeSpec);
            }
        }
    }

    public void addTypeSpec(ClassCategory classCategory, T typeSpec) {
        List<T> specList = this.typeSpecs.computeIfAbsent(classCategory, n -> new ArrayList<>());
        specList.add(typeSpec);
    }

    abstract void writeFile(Path resultDirectory, String packageName, T typeSpec) throws IOException;
}
