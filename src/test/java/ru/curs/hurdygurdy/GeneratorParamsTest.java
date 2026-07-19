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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratorParamsTest {

    @Test
    void defaultFrameworkIsSpring() {
        assertThat(GeneratorParams.rootPackage("com.example").getFramework())
                .isEqualTo(Framework.SPRING);
    }

    @Test
    void frameworkIsSettable() {
        assertThat(GeneratorParams.rootPackage("com.example")
                .framework(Framework.QUARKUS).getFramework())
                .isEqualTo(Framework.QUARKUS);
    }

    @Test
    void ofParsesCaseInsensitively() {
        assertThat(Framework.of("quarkus")).isEqualTo(Framework.QUARKUS);
        assertThat(Framework.of("QUARKUS")).isEqualTo(Framework.QUARKUS);
        assertThat(Framework.of("spring")).isEqualTo(Framework.SPRING);
    }

    @Test
    void ofDefaultsToSpringForBlankOrNull() {
        assertThat(Framework.of(null)).isEqualTo(Framework.SPRING);
        assertThat(Framework.of("  ")).isEqualTo(Framework.SPRING);
    }

    @Test
    void defaultGenerateIsControllerOnly() {
        assertThat(GeneratorParams.rootPackage("com.example").getGenerate())
                .containsExactly(Role.CONTROLLER);
    }

    @Test
    void generateIsSettableAndReplacesSelection() {
        assertThat(GeneratorParams.rootPackage("com.example")
                .generate(Role.CLIENT).getGenerate())
                .containsExactly(Role.CLIENT);
        assertThat(GeneratorParams.rootPackage("com.example")
                .generate(Role.CONTROLLER, Role.API, Role.CLIENT).getGenerate())
                .containsExactly(Role.CONTROLLER, Role.API, Role.CLIENT);
    }

    @Test
    void generateRejectsEmptySelection() {
        assertThatThrownBy(() -> GeneratorParams.rootPackage("com.example").generate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void generateApiInterfaceAddsApiToSelection() {
        GeneratorParams params = GeneratorParams.rootPackage("com.example")
                .generateApiInterface(true);
        assertThat(params.getGenerate()).containsExactly(Role.CONTROLLER, Role.API);
        params.generateApiInterface(false);
        assertThat(params.getGenerate()).containsExactly(Role.CONTROLLER);
    }

    @Test
    void roleParsesCaseInsensitivelyAndDefaults() {
        assertThat(Role.parse("client")).containsExactly(Role.CLIENT);
        assertThat(Role.parse("CONTROLLER, Api")).containsExactly(Role.CONTROLLER, Role.API);
        assertThat(Role.parse("controller,api,client"))
                .containsExactly(Role.CONTROLLER, Role.API, Role.CLIENT);
        assertThat(Role.parse(null)).containsExactly(Role.CONTROLLER);
        assertThat(Role.parse("  ")).containsExactly(Role.CONTROLLER);
    }

    @Test
    void generateAliasAsModelDefaultsToFalse() {
        assertThat(GeneratorParams.rootPackage("com.example").isGenerateAliasAsModel())
                .isFalse();
    }

    @Test
    void generateAliasAsModelBuilderOverrides() {
        assertThat(GeneratorParams.rootPackage("com.example")
                .generateAliasAsModel(true).isGenerateAliasAsModel())
                .isTrue();
    }

    @Test
    void javaDtoStyleDefaultsToLombok() {
        assertEquals(JavaDtoStyle.LOMBOK,
                GeneratorParams.rootPackage("com.example").getJavaDtoStyle());
    }

    @Test
    void javaDtoStyleBuilderOverrides() {
        assertEquals(JavaDtoStyle.RECORDS,
                GeneratorParams.rootPackage("com.example")
                        .javaDtoStyle(JavaDtoStyle.RECORDS).getJavaDtoStyle());
    }

    @Test
    void javaDtoStyleOfParsesCaseInsensitivelyAndDefaults() {
        assertEquals(JavaDtoStyle.POJO, JavaDtoStyle.of("PoJo"));
        assertEquals(JavaDtoStyle.LOMBOK, JavaDtoStyle.of(null));
        assertEquals(JavaDtoStyle.LOMBOK, JavaDtoStyle.of("  "));
    }
}
