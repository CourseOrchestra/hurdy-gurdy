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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void leadingUnderscoreProperty() throws IOException {
        // hurdy-gurdy#566: a leading underscore is valid snake_case. Locks the
        // shape: the underscore survives into the field name, and an explicit
        // @JsonProperty pins the wire name, which @JsonNaming(SnakeCaseStrategy)
        // would otherwise mangle (it eats the first leading underscore).
        codegen.generate(Path.of("src/test/resources/issue566.yaml"), result);
        verify(result);
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
    void binaryArrayMultipartPartSpring() throws IOException {
        // hurdy-gurdy#618: a multipart part that is an ARRAY of `format: binary`
        // is a repeated upload, so it must be List<MultipartFile> - it used to
        // fall through to the DTO mapping of a bare binary schema and come out
        // List<byte[]>. An array NAMED by a same-file alias counts: the alias is
        // inlined at the point of use, so the part is the same repeated upload.
        // The snapshot also holds the neighbours the fix must not disturb: a
        // non-binary array part (spelled out and aliased), and the binary
        // properties of the JSON DTO, which stay byte[] / List<byte[]> (base64).
        codegen.generate(Path.of("src/test/resources/issue618.yaml"), result);
        verify(result);
    }

    @Test
    void binaryArrayMultipartPartAliasAsModel() throws IOException {
        // The boundary of the alias case above: with generateAliasAsModel the
        // alias is NOT inlined anywhere, so the part keeps the generated class
        // (FileList extends ArrayList<byte[]>) exactly as every other use of it
        // does. Turning that class into a list of uploads would be wrong - the
        // same component may carry base64 in a JSON DTO - so the flag remains
        // the way to say "this alias is a model", upload part or not.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateAliasAsModel(true));
        codegen.generate(Path.of("src/test/resources/issue618.yaml"), result);
        verify(result);
    }

    @Test
    void binaryArrayMultipartPartQuarkus() throws IOException {
        // Same part, Quarkus upload type: List<FileUpload>.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .framework(Framework.QUARKUS).generateResponseParameter(false));
        codegen.generate(Path.of("src/test/resources/issue618.yaml"), result);
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
    void typeArray31() throws IOException {
        // hurdy-gurdy#603: `type: [X, "null"]` is the OpenAPI 3.1 spelling of a
        // nullable X. Java used to mint an empty DTO class named after the
        // property (Opt, Num, Arr...) for every such schema.
        codegen.generate(Path.of("src/test/resources/typearray31.yaml"), result);
        verify(result);
    }

    @Test
    void strayNullable31IsIgnored() throws IOException {
        // 3.1 removed the `nullable` keyword, so a 3.1 document carrying it says
        // nothing: `req_nullable` is required and therefore NOT nullable here.
        codegen.generate(Path.of("src/test/resources/straynullable31.yaml"), result);
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
    void headerDefaultsAndHyphenatedNamesSpring() throws IOException {
        // The Java counterpart of KCodegenTest.nullabilityHonoursRequiredAndDefault,
        // and the fixture that had no Java coverage at all. Two things it pins,
        // both of which used to be wrong here and right in Kotlin:
        // `X-Trace-Id` becomes the identifier xTraceId (CaseUtils.kebabToCamel used
        // to leave the hyphen in, which JavaPoet rejects outright, so generation
        // failed on any spec with a hyphenated header), and an optional header
        // carrying a default gets that default into @RequestHeader — without it the
        // parameter arrives null where the specification promised a value.
        // Controller and client together, since each builds its parameters separately.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .forceSnakeCaseForProperties(false)
                .generate(Role.CONTROLLER, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/issue617.yaml"), result);
        verify(result);
    }

    @Test
    void headerDefaultsAndHyphenatedNamesQuarkus() throws IOException {
        // The Quarkus resource builds its own parameter list, so it needs its own
        // coverage: here the default reaches the parameter as a separate
        // @DefaultValue annotation, which the Java extractor emitted for query
        // parameters only and never for headers.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .forceSnakeCaseForProperties(false)
                .framework(Framework.QUARKUS)
                .generate(Role.CONTROLLER, Role.CLIENT));
        codegen.generate(Path.of("src/test/resources/issue617.yaml"), result);
        verify(result);
    }

    @Test
    void externalRefNullabilityAndDefaults() throws IOException {
        // The Java counterpart of the identically named Kotlin test. A default
        // declared on a component in another file has to reach the annotation of
        // every parameter that $refs it: the Java extractor read
        // schema.getDefault() directly, which is null for a $ref because the
        // parser is not asked to resolve one, and silently dropped the default.
        // Snapshot only: the referenced types live in another package and are not
        // generated here.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .generateResponseParameter(false)
                .forceSnakeCaseForProperties(false));
        codegen.generate(Path.of("src/test/resources/externalnullable.yaml"), result);
        Approvals.verify(getContent(result));
    }

    /**
     * An operation whose verb the generator cannot map stops generation, in
     * every dialect, rather than emitting a method with no mapping annotation.
     *
     * <p>Such a method compiles, so nothing in the user's build complains, and
     * the endpoint is simply never routed — the failure shows up in production.
     * Before the framework bindings were extracted the three dialects disagreed
     * about this: the Spring controller threw a bare NullPointerException from
     * inside JavaPoet, while the Spring client and Quarkus emitted the unmapped
     * method. One algorithm, one answer.
     */
    @ParameterizedTest
    @EnumSource(Framework.class)
    void unsupportedHttpMethodIsRejected(Framework framework) {
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .framework(framework)
                .generate(EnumSet.allOf(Role.class)));
        assertThatThrownBy(() ->
                codegen.generate(Path.of("src/test/resources/unsupportedverb.yaml"), result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported HTTP method 'options' at path '/items'")
                .hasMessageContaining("get, post, put, patch and delete");
    }

    @Test
    void externalNullableRequiredRecordComponent() throws IOException {
        // A required record component is null-checked in the compact constructor
        // unless the component it references permits null. When that component is
        // declared in another file the question has to be put to THAT document:
        // asking the current one finds nothing, answers "not nullable" by default
        // and emits a check that rejects the legal payload {"thing": null}. The
        // records path used to ask the wrong document; it now shares the one
        // answer the rest of the generator has used since issue 620.
        // Snapshot only: the referenced type lives in another package.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false)
                .javaDtoStyle(JavaDtoStyle.RECORDS));
        codegen.generate(Path.of("src/test/resources/externalrecordnullable.yaml"), result);
        Approvals.verify(getContent(result));
    }

    @ParameterizedTest
    @EnumSource(JavaDtoStyle.class)
    void clashingGeneratedNamesAreRejected(JavaDtoStyle style) {
        // Two inline objects with the same title generate two different classes
        // under one name. Because a generated name is a file name, the second
        // silently overwrote the first and one of the two properties ended up
        // typed by a class carrying the other's fields — output that compiles,
        // which is what made it dangerous.
        codegen = new JavaCodegen(GeneratorParams.rootPackage("com.example")
                .forceSnakeCaseForProperties(false)
                .javaDtoStyle(style));
        assertThatThrownBy(() ->
                codegen.generate(Path.of("src/test/resources/titleclash.yaml"), result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both generated as 'Shared'");
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