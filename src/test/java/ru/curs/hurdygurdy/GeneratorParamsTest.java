package ru.curs.hurdygurdy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void defaultRoleIsServer() {
        assertThat(GeneratorParams.rootPackage("com.example").getRole())
                .isEqualTo(Role.SERVER);
    }

    @Test
    void roleIsSettable() {
        assertThat(GeneratorParams.rootPackage("com.example")
                .role(Role.CLIENT).getRole()).isEqualTo(Role.CLIENT);
    }

    @Test
    void roleOfParsesCaseInsensitivelyAndDefaults() {
        assertThat(Role.of("client")).isEqualTo(Role.CLIENT);
        assertThat(Role.of("SERVER")).isEqualTo(Role.SERVER);
        assertThat(Role.of(null)).isEqualTo(Role.SERVER);
        assertThat(Role.of("  ")).isEqualTo(Role.SERVER);
    }
}
