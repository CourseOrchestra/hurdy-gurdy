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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The architecture, as rules rather than as prose.
 *
 * <p>The generator is a pipeline: the specification is read and normalised
 * ({@code spec}), reduced to language-neutral facts ({@code model}), walked to
 * produce output ({@code extract}) with the help of a framework vocabulary
 * ({@code binding}) and a per-language type system ({@code emit}). The root
 * package holds the published API - the orchestrators and the configuration
 * value objects - and {@code maven} is one of the front ends.
 *
 * <p>Every rule below states a decision taken during the refactoring and says
 * why in its {@code because} clause. They are checked against bytecode, so a
 * dependency introduced through a return type or a lambda counts just as much
 * as one written as an import.
 */
class ArchitectureTest {

    private static final String ROOT = "ru.curs.hurdygurdy";
    private static final String SPEC = ROOT + ".spec";
    private static final String MODEL = ROOT + ".model";
    private static final String EXTRACT = ROOT + ".extract";
    private static final String BINDING = ROOT + ".binding";
    private static final String EMIT = ROOT + ".emit";
    private static final String MAVEN = ROOT + ".maven";

    private static final String JAVAPOET = "com.palantir.javapoet..";
    private static final String KOTLINPOET = "com.squareup.kotlinpoet..";

    /** Anything of ours whose simple name marks it as the Kotlin half of a Strategy pair. */
    private static final String OWN_KOTLIN_CLASS = "ru\\.curs\\.hurdygurdy(\\..+)?\\.Kotlin.*";
    /** ... and the Java half. */
    private static final String OWN_JAVA_CLASS = "ru\\.curs\\.hurdygurdy(\\..+)?\\.Java.*";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * ArchUnit fails a rule whose {@code that()} clause selects nothing, so the
     * subjects below cannot silently go vacuous. The package names inside a
     * {@code dependOnClassesThat()} clause get no such check: rename a package and
     * every rule that merely forbids depending on it would quietly go green. Those
     * names are the ones verified here.
     */
    @Test
    void everyLayerNamedBelowExists() {
        for (String layer : List.of(ROOT, SPEC, MODEL, EXTRACT, BINDING, EMIT, MAVEN)) {
            assertTrue(PRODUCTION_CLASSES.stream().anyMatch(c -> layer.equals(c.getPackageName())),
                    () -> "no class found in " + layer + ": the rules naming it are vacuous");
        }
        for (String prefix : List.of("Java", "Kotlin")) {
            assertTrue(PRODUCTION_CLASSES.stream().anyMatch(c -> c.getSimpleName().startsWith(prefix)),
                    () -> "no class whose name starts with " + prefix);
        }
    }

    // ------------------------------------------------------------------ layering

    @Test
    void specIsALeaf() {
        noClasses().that().resideInAPackage(SPEC)
                .should().dependOnClassesThat().resideInAnyPackage(ROOT, MODEL, EXTRACT, BINDING, EMIT, MAVEN)
                .because("the spec layer answers questions about an OpenAPI document: what a schema means, "
                        + "what an allOf resolves to, which file a $ref came from. None of those answers may "
                        + "depend on there being a generator, let alone on what it emits")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void emitDependsOnNothingAboveIt() {
        noClasses().that().resideInAPackage(EMIT)
                .should().dependOnClassesThat().resideInAnyPackage(MODEL, EXTRACT, BINDING, MAVEN)
                .because("a type definer turns a schema into a type name; it is called by the extractors "
                        + "and must not reach back up into them")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void bindingsSupplyVocabularyAndNothingElse() {
        noClasses().that().resideInAPackage(BINDING)
                .should().dependOnClassesThat().resideInAnyPackage(SPEC, EMIT, EXTRACT, MAVEN)
                .because("a binding is a Strategy holding the names a framework uses. The algorithm that "
                        + "consumes them lives in the extractors, and a binding that started reading the "
                        + "specification for itself is how the dialects drifted apart in the first place")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void modelDependsOnNothingAboveIt() {
        noClasses().that().resideInAPackage(MODEL)
                .should().dependOnClassesThat().resideInAnyPackage(EXTRACT, BINDING, MAVEN)
                .because("the model is what the document says, not what anyone does with it")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void modelReachesIntoEmitOnlyForTheTypeDefiner() {
        noClasses().that().resideInAPackage(MODEL)
                .should().dependOnClassesThat(resideInAPackage(EMIT).and(not(name(EMIT + ".TypeDefiner"))))
                .because("ApiModelBuilder takes a TypeDefiner because resolving a schema to a type is the "
                        + "one genuinely per-language step it cannot do itself. That is the whole of the "
                        + "model's business with emit, and it is pinned here so it does not grow before "
                        + "the TypeModel IR removes it")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void frameworkDialectsAreChosenInOnePlace() {
        noClasses().that().resideOutsideOfPackages(BINDING, EXTRACT)
                .should().dependOnClassesThat().resideInAPackage(BINDING)
                .because("which of Spring MVC, the Spring HTTP interface or Quarkus is in play is decided "
                        + "once, where the extractor picks its binding; the rest of the generator never "
                        + "sees a dialect")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void nothingDependsOnTheMavenFrontEnd() {
        noClasses().that().resideOutsideOfPackage(MAVEN)
                .should().dependOnClassesThat().resideInAPackage(MAVEN)
                .because("the Maven plugin is a front end. Main and the Gradle plugin are the others, and "
                        + "a core that knew about any one of them could not be used by the rest")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theMavenFrontEndUsesOnlyThePublishedApi() {
        noClasses().that().resideInAPackage(MAVEN)
                .should().dependOnClassesThat().resideInAnyPackage(SPEC, MODEL, EXTRACT, BINDING, EMIT)
                .because("a front end configures GeneratorParams and calls a Codegen. If it needs anything "
                        + "deeper, the published API is missing something")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void thePipelineStagesAreAcyclic() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles()
                .because("spec, model, extract, binding, emit and maven form a pipeline. The root package "
                        + "is deliberately outside this check: it is both the shared kernel of "
                        + "configuration value objects and the orchestrator on top, which is the one knot "
                        + "left to untie")
                .check(PRODUCTION_CLASSES);
    }

    // -------------------------------------------------- third-party confinement

    @Test
    void theLanguageNeutralLayersKnowNoCodeGenerationLibrary() {
        noClasses().that().resideInAnyPackage(SPEC, MODEL)
                .should().dependOnClassesThat().resideInAnyPackage(JAVAPOET, KOTLINPOET)
                .because("these layers exist to be read once and used by both back ends; a JavaPoet type "
                        + "in either of them is a fact answered per language again")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theTypeDefinerBaseClassKnowsNoCodeGenerationLibrary() {
        noClasses().that().haveNameMatching(EMIT + "\\.TypeDefiner")
                .should().dependOnClassesThat().resideInAnyPackage(JAVAPOET, KOTLINPOET)
                .because("TypeDefiner<T> produces T and must not know what T is. It once declared both "
                        + "defineJavaType and defineKotlinType with throwing bodies, so it imported both "
                        + "libraries and <T> constrained nothing")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theJavaBackEndNeverTouchesKotlinPoet() {
        noClasses().that().haveSimpleNameStartingWith("Java")
                .should().dependOnClassesThat().resideInAnyPackage(KOTLINPOET)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theKotlinBackEndNeverTouchesJavaPoet() {
        noClasses().that().haveSimpleNameStartingWith("Kotlin")
                .should().dependOnClassesThat().resideInAnyPackage(JAVAPOET)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theTwoBackEndsDoNotReferenceEachOther() {
        noClasses().that().haveSimpleNameStartingWith("Java")
                .should().dependOnClassesThat().haveNameMatching(OWN_KOTLIN_CLASS)
                .because("the two are Strategy implementations of the same abstractions, not collaborators; "
                        + "they meet only at Codegen, TypeDefiner and the shared spec and model layers")
                .check(PRODUCTION_CLASSES);

        noClasses().that().haveSimpleNameStartingWith("Kotlin")
                .should().dependOnClassesThat().haveNameMatching(OWN_JAVA_CLASS)
                .because("the same in the other direction")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void frameworkAnnotationsLiveOnlyInTheBindings() {
        noClasses().that().resideOutsideOfPackage(BINDING)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta..", "org.apache.tomcat..")
                .because("Spring, Jakarta and Servlet types are here as annotation and parameter "
                        + "vocabulary for generated code. Only a binding names them, which is what keeps "
                        + "them out of the generator's own logic")
                .check(PRODUCTION_CLASSES);
    }

    // Lombok is a compile-scope dependency purely so the generator can name the
    // annotations it writes into other people's code; hurdy-gurdy's own sources use
    // none of it. That second half cannot be checked here - Lombok's annotations are
    // SOURCE retention and leave no trace in bytecode for ArchUnit to see.
    @Test
    void theDtoStyleVocabularyStaysInItsOwnStrategy() {
        noClasses().that().haveNameNotMatching(EMIT + "\\.JavaLombokMembers")
                .should().dependOnClassesThat().resideInAnyPackage("lombok..")
                .because("Lombok is vocabulary on a different axis from a framework binding: @Data is a "
                        + "DTO shape, not an HTTP dialect. JavaClassMembers is the Strategy that carries "
                        + "it and JavaLombokMembers its one implementation that names Lombok. Let @Data "
                        + "reach JavaTypeDefiner and the single class-building path grows style tests "
                        + "again, which is exactly what the POJO and records styles cost before")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theMavenPluginApiLivesOnlyInTheMavenFrontEnd() {
        noClasses().that().resideOutsideOfPackage(MAVEN)
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.maven..")
                .because("maven-plugin-api and maven-core are provided-scope: outside the Mojo they are "
                        + "not on the classpath at all, and neither the CLI jar nor the native image "
                        + "contains them")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void commandLineParsingLivesOnlyInMain() {
        noClasses().that().haveNameNotMatching("ru\\.curs\\.hurdygurdy\\.Main(\\$.*)?")
                .should().dependOnClassesThat().resideInAnyPackage("picocli..")
                .because("picocli is an optional dependency, present for the CLI jar only")
                .check(PRODUCTION_CLASSES);
    }

    // -------------------------------------------------------- shape and hygiene

    @Test
    void theModelIsValueObjects() {
        classes().that().resideInAPackage(MODEL)
                .and().areNotNestedClasses()
                .and().doNotHaveSimpleName("package-info")
                .and().haveSimpleNameNotEndingWith("Builder")
                .should().beRecords()
                .because("a model type carries facts read out of the document and nothing else; the one "
                        + "class that does the reading is the builder")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void warningsGoToTheWarningListenerRatherThanToTheConsole() {
        noClasses().that().haveNameNotMatching("ru\\.curs\\.hurdygurdy\\.Codegen")
                .should(ACCESS_STANDARD_STREAMS)
                .because("a generator embedded in a Maven or Gradle build must report through the build "
                        + "log. Codegen is the single exception: it defaults its warningListener to "
                        + "System.err for the case where no front end sets one")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void failuresNameTheirCause() {
        NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(PRODUCTION_CLASSES);
        NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(PRODUCTION_CLASSES);
    }

    @Test
    void theEntryPointsNamedByTheBuildStayWhereTheBuildExpectsThem() {
        classes().that().haveSimpleName("Main").should().resideInAPackage(ROOT)
                .because("the shaded CLI jar's manifest and the native-image configuration in pom.xml "
                        + "both name ru.curs.hurdygurdy.Main, and it is public API besides; moving it "
                        + "once broke the end-to-end CI job")
                .check(PRODUCTION_CLASSES);

        classes().that().haveSimpleName("CodegenMojo").should().resideInAPackage(MAVEN)
                .check(PRODUCTION_CLASSES);
    }
}
