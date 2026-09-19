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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratorParamsTest {

    private static final String ROOT = "com.example";

    // ------------------------------------------------------- parsing a name

    // The four parsers share one contract, so they share one table shape:
    // case-insensitive, surrounding whitespace tolerated, and "said nothing" -
    // null, empty or blank - means the documented default. `null` below is a
    // genuine null reference (nullValues), which is what a front end passes
    // when its option was never set; `''` and `'   '` are what it passes when
    // the option was set to nothing, and the three must not be told apart.

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "quarkus,    QUARKUS",   // as documented
            "QUARKUS,    QUARKUS",   // case-insensitive
            "' Spring ', SPRING",    // surrounding whitespace trimmed
            "null,       SPRING",    // never configured
            "'',         SPRING",    // configured empty
            "'   ',      SPRING",    // configured blank
    })
    void frameworkOfParsesOrDefaultsToSpring(String value, Framework expected) {
        assertThat(Framework.of(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "kotlin,   KOTLIN",
            "KOTLIN,   KOTLIN",
            "' Java ', JAVA",
            "null,     JAVA",
            "'',       JAVA",
            "'   ',    JAVA",
    })
    void languageOfParsesOrDefaultsToJava(String value, Language expected) {
        assertThat(Language.of(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "PoJo,       POJO",
            "records,    RECORDS",
            "' lombok ', LOMBOK",
            "null,       LOMBOK",
            "'',         LOMBOK",
            "'   ',      LOMBOK",
    })
    void javaDtoStyleOfParsesOrDefaultsToLombok(String value, JavaDtoStyle expected) {
        assertThat(JavaDtoStyle.of(value)).isEqualTo(expected);
    }

    // A semicolon delimiter, because both columns are themselves comma-separated.
    @ParameterizedTest
    @CsvSource(delimiter = ';', nullValues = "null", value = {
            "client;                CLIENT",
            "CONTROLLER, Api;       CONTROLLER,API",          // spaces round the comma
            "controller,api,client; CONTROLLER,API,CLIENT",
            "client,controller;     CONTROLLER,CLIENT",       // ordinal order, not written order
            "null;                  CONTROLLER",              // never configured
            "'';                    CONTROLLER",              // configured empty
            "'   ';                 CONTROLLER",              // configured blank
            "',,';                  CONTROLLER",              // separators and nothing else
    })
    void roleParseReadsAListOrDefaultsToController(String value, String expected) {
        assertThat(Role.parse(value)).containsExactlyElementsOf(roles(expected));
    }

    /**
     * A misspelt name is an error, not a silent default.
     */
    @Test
    void anUnknownNameIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> Language.of("jva")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Framework.of("sprong")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JavaDtoStyle.of("lambok")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Role.parse("contoller")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A language this generator does not emit is an error, not a silent fallback
     * to the one it does.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "rust",         // a real language, just not one hurdy-gurdy generates
            "Rust",         // ... in any casing
            "  rust  ",     // ... however padded
            "scala",
            "javascript",   // shares a prefix with a language that IS supported
    })
    void languageOfRejectsAnythingItCannotGenerate(String value) {
        assertThatThrownBy(() -> Language.of(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(value.trim().toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------ back-end selection

    @ParameterizedTest
    @CsvSource({
            "JAVA,   ru.curs.hurdygurdy.JavaCodegen",
            "KOTLIN, ru.curs.hurdygurdy.KotlinCodegen",
    })
    void codegenOfSelectsTheBackEndForTheLanguage(Language language, Class<?> expected) {
        assertThat(Codegen.of(language, GeneratorParams.rootPackage(ROOT))).isInstanceOf(expected);
    }

    // ----------------------------------------------------------- the defaults

    @Test
    void anUnconfiguredParamsCarriesTheDocumentedDefaults() {
        GeneratorParams params = GeneratorParams.rootPackage(ROOT);
        assertThat(params.getRootPackage()).isEqualTo(ROOT);
        assertThat(params.getFramework()).isEqualTo(Framework.SPRING);
        assertThat(params.getJavaDtoStyle()).isEqualTo(JavaDtoStyle.LOMBOK);
        assertThat(params.getGenerate()).containsExactly(Role.CONTROLLER);
        assertThat(params.isGenerateResponseParameter()).isFalse();
        assertThat(params.isForceSnakeCaseForProperties()).isTrue();
        assertThat(params.isGenerateAliasAsModel()).isFalse();
    }

    // ------------------------------------------------------ the fluent setters

    @ParameterizedTest
    @CsvSource({
            "SPRING,  LOMBOK",
            "QUARKUS, RECORDS",
            "QUARKUS, POJO",
    })
    void frameworkAndDtoStyleAreSettable(Framework framework, JavaDtoStyle style) {
        GeneratorParams params = GeneratorParams.rootPackage(ROOT)
                .framework(framework)
                .javaDtoStyle(style);
        assertThat(params.getFramework()).isEqualTo(framework);
        assertThat(params.getJavaDtoStyle()).isEqualTo(style);
    }

    // One row per value rather than per flag: the three booleans differ only in
    // their default, which the defaults test above pins.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyBooleanFlagRoundTrips(boolean value) {
        GeneratorParams params = GeneratorParams.rootPackage(ROOT)
                .generateResponseParameter(value)
                .forceSnakeCaseForProperties(value)
                .generateAliasAsModel(value);
        assertThat(params.isGenerateResponseParameter()).isEqualTo(value);
        assertThat(params.isForceSnakeCaseForProperties()).isEqualTo(value);
        assertThat(params.isGenerateAliasAsModel()).isEqualTo(value);
    }

    // ---------------------------------------------------- the generated roles

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "CLIENT;                CLIENT",
            "CONTROLLER,API,CLIENT; CONTROLLER,API,CLIENT",
            "CLIENT,CONTROLLER;     CONTROLLER,CLIENT",   // ordinal order, not written order
    })
    void generateReplacesTheWholeSelection(String selected, String expected) {
        assertThat(GeneratorParams.rootPackage(ROOT).generate(roles(selected)).getGenerate())
                .containsExactlyElementsOf(roles(expected));
    }

    @Test
    void generateAcceptsVarargsAsWellAsAnIterable() {
        assertThat(GeneratorParams.rootPackage(ROOT).generate(Role.API, Role.CLIENT).getGenerate())
                .containsExactly(Role.API, Role.CLIENT);
    }

    @Test
    void generateRejectsEmptySelection() {
        assertThatThrownBy(() -> GeneratorParams.rootPackage(ROOT).generate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A toggle rather than a table: what is being tested is the transition, so
    // the two calls have to happen to the same instance in this order.
    @Test
    @SuppressWarnings("deprecation")
    void generateApiInterfaceAddsApiToSelection() {
        GeneratorParams params = GeneratorParams.rootPackage(ROOT)
                .generateApiInterface(true);
        assertThat(params.getGenerate()).containsExactly(Role.CONTROLLER, Role.API);
        params.generateApiInterface(false);
        assertThat(params.getGenerate()).containsExactly(Role.CONTROLLER);
    }

    private static List<Role> roles(String names) {
        return Arrays.stream(names.split(",")).map(String::trim).map(Role::valueOf).toList();
    }
}
