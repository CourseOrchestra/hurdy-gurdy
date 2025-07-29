package ru.curs.hurdygurdy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.curs.hurdygurdy.CaseUtils.normalizeToCamel;
import static ru.curs.hurdygurdy.CaseUtils.normalizeToScreamingSnake;

class CaseUtilsTest {
    @Test
    void snakeToCamel() {
        assertThat(CaseUtils.snakeToCamel("this_is_snake")).isEqualTo("thisIsSnake");
    }

    @Test
    void snakeToCamelNull() {
        assertThat(CaseUtils.snakeToCamel(null)).isNull();
    }

    @Test
    void snakeToCamelBlank() {
        assertThat(CaseUtils.snakeToCamel("")).isEmpty();
    }

    @Test
    void snakeToCamelUnderscore() {
        assertThat(CaseUtils.snakeToCamel("_")).isEqualTo("_");
    }

    @Test
    void snakeToCamelMultiUnderscore() {
        assertThat(CaseUtils.snakeToCamel("__")).isEqualTo("__");
    }

    @Test
    void snakeToCamelMultiUnderscoreInText() {
        assertThat(CaseUtils.snakeToCamel("__this__is__snake_")).isEqualTo("__thisIsSnake");
    }

    @Test
    void snakeToCamelAlreadyCamel() {
        assertThat(CaseUtils.snakeToCamel("thisIsCamel")).isEqualTo("thisIsCamel");
    }

    @Test
    void kebabToCamel() {
        assertThat(CaseUtils.kebabToCamel("this-is-kebab")).isEqualTo("thisIsKebab");
    }

    @Test
    void kebabPascalToCamel() {
        assertThat(CaseUtils.kebabToCamel("This-Is-Pascal-Kebab")).isEqualTo("thisIsPascalKebab");
    }

    @Test
    void kebabToCamelNull() {
        assertThat(CaseUtils.kebabToCamel(null)).isNull();
    }

    @Test
    void kebabToCamelBlank() {
        assertThat(CaseUtils.kebabToCamel("")).isEmpty();
    }

    @Test
    void kebabToCamelMultiHyphenInText() {
        assertThat(CaseUtils.kebabToCamel("This--Is---Pascal--Kebab")).isEqualTo("thisIsPascalKebab");
    }

    @Test
    void kebabToCamelAlreadyCamel() {
        assertThat(CaseUtils.kebabToCamel("thisIsCamel")).isEqualTo("thisIsCamel");
    }

    @Test
    void pathToCamelNull() {
        assertThat(CaseUtils.pathToCamel(null)).isNull();
    }

    @Test
    void pathToCamelBlank() {
        assertThat(CaseUtils.pathToCamel("")).isEmpty();
    }


    @Test
    void pathToCamel() {
        assertThat(CaseUtils.pathToCamel("/path/to/camel")).isEqualTo("pathToCamel");
        assertThat(CaseUtils.pathToCamel("//path//to//camel")).isEqualTo("pathToCamel");
    }


    @Test
    void pathToCamelSinglePart() {
        assertThat(CaseUtils.pathToCamel("path")).isEqualTo("path");
        assertThat(CaseUtils.pathToCamel("/path")).isEqualTo("path");
        assertThat(CaseUtils.pathToCamel("{path}")).isEqualTo("path");
        assertThat(CaseUtils.pathToCamel("/{path}")).isEqualTo("path");

    }

    @Test
    void pathToCamelWithParameters() {
        assertThat(CaseUtils.pathToCamel(
                "/admin/customFieldSettings/bundles/build/{id}/values/{buildBundleElementId}"))
                .isEqualTo("adminCustomFieldSettingsBundlesBuildIdValuesBuildBundleElementId");
    }

    @ParameterizedTest(name = "{index} ⇒ \"{0}\"  ➜  \"{1}\"")
    @CsvSource(
            nullValues = "NULL",
            value = {
                    // input, expected
                    "NULL,                        NULL",
                    "'',                          __",
                    "'----   ..',                 __",
                    "'hello world',               HelloWorld",
                    "'my-variable.name',          MyVariableName",
                    "'123abc',                    _123abc",
                    "'99bottles-of-beer',         _99bottlesOfBeer",
                    "'an---example__name',        AnExample__name",
                    "'const',                     Const",
                    "'foo',                       Foo",
                    "'Foo',                       Foo"
            })
    void normalizeToCamelTest(String input, String expected) {
        assertThat(normalizeToCamel(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{index} ⇒ \"{0}\"  ➜  \"{1}\"")
    @CsvSource(
            nullValues = "NULL",
            value = {
                    // input, expected
                    "NULL,                        NULL",
                    "'',                          __",
                    "'----   ..',                 __",
                    "'hello world',               HELLO_WORLD",
                    "'my-variable.name',          MY_VARIABLE_NAME",
                    "'123abc',                    _123ABC",
                    "'99bottles-of-beer',         _99BOTTLES_OF_BEER",
                    "'an---example__name',        AN_EXAMPLE__NAME",
                    "'const',                     CONST",
                    "'foo',                       FOO",
                    "'FOO',                       FOO"
            })
    void normalizeToScreamingSnakeTest(String input, String expected) {
        assertThat(normalizeToScreamingSnake(input)).isEqualTo(expected);
    }

}
