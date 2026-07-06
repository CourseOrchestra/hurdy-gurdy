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
}
