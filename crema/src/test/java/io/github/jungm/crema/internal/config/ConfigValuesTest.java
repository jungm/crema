package io.github.jungm.crema.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigValuesTest {

    @Test
    void integers() {
        assertEquals(0, ConfigValues.integer("k", "0", 0, "seconds"));
        assertEquals(42, ConfigValues.integer("k", " 42 ", 1, "seconds"));
        assertEquals(Long.MAX_VALUE, ConfigValues.integer("k", String.valueOf(Long.MAX_VALUE), 0, "seconds"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "-1", "0", "1.5", "soon", "", "99999999999999999999" })
    void invalidIntegers(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigValues.integer("crema.k", value, 1, "bytes"));
        assertEquals("crema.k must be an integer number of bytes >= 1, but is '" + value + "'", e.getMessage());
    }
}
