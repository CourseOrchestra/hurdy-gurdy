package ru.curs.clickmatters.hurdygurdy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseUtilsTest {
    @Test
    void snakeToCamel(){
        assertThat(CaseUtils.snakeToCamel("this_is_snake")).isEqualTo("thisIsSnake");
    }

    @Test
    void snakeToCamelNull(){
        assertThat(CaseUtils.snakeToCamel(null)).isNull();
    }

    @Test
    void snakeToCamelBlank(){
        assertThat(CaseUtils.snakeToCamel("")).isEmpty();
    }

    @Test
    void snakeToCamelUnderscore(){
        assertThat(CaseUtils.snakeToCamel("_")).isEqualTo("_");
    }

    @Test
    void snakeToCamelMultiUnderscore(){
        assertThat(CaseUtils.snakeToCamel("__")).isEqualTo("__");
    }

    @Test
    void snakeToCamelMultiUnderscoreInText(){
        assertThat(CaseUtils.snakeToCamel("__this__is__snake_")).isEqualTo("__thisIsSnake");
    }

    @Test
    void snakeToCamelAlreadyCamel(){
        assertThat(CaseUtils.snakeToCamel("thisIsCamel")).isEqualTo("thisIsCamel");
    }

    @Test
    void kebabToCamel(){
        assertThat(CaseUtils.kebabToCamel("this-is-kebab")).isEqualTo("thisIsKebab");
    }

    @Test
    void kebabPascalToCamel(){
        assertThat(CaseUtils.kebabToCamel("This-Is-Pascal-Kebab")).isEqualTo("thisIsPascalKebab");
    }

    @Test
    void kebabToCamelNull(){
        assertThat(CaseUtils.kebabToCamel(null)).isNull();
    }

    @Test
    void kebabToCamelBlank(){
        assertThat(CaseUtils.kebabToCamel("")).isEmpty();
    }

    @Test
    void kebabToCamelMultiHyphenInText(){
        assertThat(CaseUtils.kebabToCamel("This--Is---Pascal--Kebab")).isEqualTo("thisIsPascalKebab");
    }
    
    @Test
    void kebabToCamelAlreadyCamel(){
        assertThat(CaseUtils.kebabToCamel("thisIsCamel")).isEqualTo("thisIsCamel");
    }
}
