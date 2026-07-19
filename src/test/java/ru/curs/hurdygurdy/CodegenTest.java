/*
 * Copyright 2026 Ivan Ponomarev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.curs.hurdygurdy;

import com.palantir.javapoet.TypeSpec;
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
    void arrayAliasInlined() throws IOException {
        // Reproducer from OpenAPITools/openapi-generator#23988: ItemArray is an
        // alias for an array of Item. By default the alias is inlined at every
        // point of use (List<Item>) and no ItemArray class is generated.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/issue23988.yaml"), result);
        verify(result);
    }

    @Test
    void arrayAliasAsModel() throws IOException {
        // Same spec with generateAliasAsModel (mirroring openapi-generator's
        // parameter): the alias becomes a model of its own,
        // class ItemArray extends ArrayList<Item>.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false)
                .generateAliasAsModel(true));
        codegen.generate(Path.of("src/test/resources/issue23988.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileArrayAlias(JavaDtoStyle style) throws IOException {
        for (boolean aliasAsModel : new boolean[]{false, true}) {
            Path out = Files.createDirectories(result.resolve(aliasAsModel ? "model" : "inline"));
            new JavaCodegen(GeneratorParams.rootPackage("com.example")
                    .forceSnakeCaseForProperties(false)
                    .javaDtoStyle(style)
                    .generateAliasAsModel(aliasAsModel))
                    .generate(Path.of("src/test/resources/issue23988.yaml"), out);
            GeneratedCodeCompiler.assertJavaCompiles(out);
        }
    }

    @Test
    void inlineEnumWithNonIdentifierValuesCompiles() throws IOException {
        // openapi-generator#24012: an inline (property-level) enum whose values
        // are not legal Java identifiers ("about:blank", a URL) must be
        // normalized like a top-level enum. Previously the inline path emitted
        // the raw values verbatim, producing non-compiling Java.
        codegen.generate(Path.of("src/test/resources/inlineenum.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @Test
    void inlineEnumNormalized() throws IOException {
        // Locks the normalized shape: inline enum constants become
        // SCREAMING_SNAKE_CASE with @JsonProperty preserving the wire value,
        // matching the top-level enum path.
        codegen.generate(Path.of("src/test/resources/inlineenum.yaml"), result);
        verify(result);
    }

    @Test
    void objectDefaultOnRefProperty() throws IOException {
        // openapi-generator#24298: the Java generator already drops an object-level
        // default on a $ref property (Lombok fields carry no initializer), so the
        // output compiles. This locks that behavior as the parity baseline for the
        // Kotlin fix.
        codegen.generate(Path.of("src/test/resources/objectdefault.yaml"), result);
        verify(result);
    }

    @Test
    void oneOfWithDiscriminatorCompiles() throws IOException {
        // openapi-generator#23997: a schema with BOTH oneOf and a discriminator
        // previously emitted duplicate @JsonTypeInfo/@JsonSubTypes (a NAME-based
        // pair and a DEDUCTION-based pair), which does not compile. The
        // discriminator must win, leaving only the NAME-based annotations.
        codegen.generate(Path.of("src/test/resources/oneofdiscriminator.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @Test
    void oneOfWithDiscriminator() throws IOException {
        // Locks the shape: single NAME-based @JsonTypeInfo(property = "pet_type")
        // + @JsonSubTypes from the discriminator mapping; no DEDUCTION pair.
        codegen.generate(Path.of("src/test/resources/oneofdiscriminator.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileOneOfWithDiscriminator(JavaDtoStyle style) throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true).javaDtoStyle(style))
                .generate(Path.of("src/test/resources/oneofdiscriminator.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @Test
    void binaryResponseTypeSpring() throws IOException {
        // A binary (format: binary) download response must return a body type a
        // Spring message converter can write (Resource), never MultipartFile.
        codegen.generate(Path.of("src/test/resources/binarydownload.yaml"), result);
        verify(result);
    }

    @Test
    void binaryResponseTypeQuarkus() throws IOException {
        // Quarkus controller (no response envelope) returns the body type; a binary
        // response must be InputStream, never FileUpload.
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .framework(Framework.QUARKUS).generateResponseParameter(false))
                .generate(Path.of("src/test/resources/binarydownload.yaml"), result);
        verify(result);
    }

    @Test
    void binaryRequestBodyTypeSpring() throws IOException {
        // A raw (non-multipart) octet-stream request body must be a readable body
        // type (Resource), never MultipartFile.
        codegen.generate(Path.of("src/test/resources/binaryrawbody.yaml"), result);
        verify(result);
    }

    @Test
    void binaryRequestBodyTypeQuarkus() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .framework(Framework.QUARKUS).generateResponseParameter(false))
                .generate(Path.of("src/test/resources/binaryrawbody.yaml"), result);
        verify(result);
    }

    @Test
    void binaryDtoPropertyJava() throws IOException {
        // A binary property inside a JSON DTO must be byte[] (base64), never
        // MultipartFile.
        codegen.generate(Path.of("src/test/resources/binaryproperty.yaml"), result);
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

    @Test
    void pojoSample2() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.POJO))
                .generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void recordsSample2() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of("src/test/resources/sample2.yaml"), result);
        verify(result);
    }

    @Test
    void pojoInheritance() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.POJO))
                .generate(Path.of("src/test/resources/pr233_inheritance.yaml"), result);
        verify(result);
    }

    @Test
    void recordsInheritance() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of("src/test/resources/pr233_inheritance.yaml"), result);
        verify(result);
    }

    @Test
    void pojoOneOf() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.POJO))
                .generate(Path.of("src/test/resources/oneofsupport.yaml"), result);
        verify(result);
    }

    @Test
    void recordsOneOf() throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of("src/test/resources/oneofsupport.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileSample2(JavaDtoStyle style) throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true).javaDtoStyle(style))
                .generate(Path.of("src/test/resources/sample2.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileInheritance(JavaDtoStyle style) throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true).javaDtoStyle(style))
                .generate(Path.of("src/test/resources/pr233_inheritance.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileDictionary(JavaDtoStyle style) throws IOException {
        // dictionarySupport.yaml does not exist in this repo; the real spec
        // exercising dictionary/additionalProperties support is dictionary.yaml.
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true).javaDtoStyle(style))
                .generate(Path.of("src/test/resources/dictionary.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void allStylesCompileDeepInheritance(JavaDtoStyle style) throws IOException {
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true).javaDtoStyle(style))
                .generate(Path.of("src/test/resources/deep_inheritance.yaml"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
    }

    @Test
    void childlessDiscriminatorRecordIsInstantiable() throws IOException {
        // In RECORDS style a discriminator base with no subtypes would become a
        // bare, uninstantiable interface. It must fall back to a concrete record
        // (keeping @JsonTypeInfo) so it can be created/deserialized, while a
        // discriminator base WITH children (Animal) stays a sealed interface. The
        // snapshot locks both shapes.
        new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(true)
                .javaDtoStyle(JavaDtoStyle.RECORDS))
                .generate(Path.of("src/test/resources/childlessDiscriminator.yaml"), result);
        verify(result);
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void youtrackCompilesInAllStyles(JavaDtoStyle style) throws IOException {
        // Real-world stress: deep allOf chains, redeclared/narrowed inherited
        // properties, illegal identifiers, camelCase props (snake-case check off).
        // Compile-only: no snapshot for 200+ files. Every role is covered so the
        // whole generation surface is exercised on a real spec in each DTO style.
        new JavaCodegen(GeneratorParams.rootPackage("org.youtrack")
                .generateResponseParameter(true)
                .forceSnakeCaseForProperties(false)
                .javaDtoStyle(style)
                .generate(EnumSet.allOf(Role.class)))
                .generate(Path.of("src/test/resources/youtrack_openapi.json"), result);
        GeneratedCodeCompiler.assertJavaCompiles(result);
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