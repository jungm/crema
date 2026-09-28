package io.github.jungm.crema.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetaKeysTest {

    @Test
    void reservedPrefixes() {
        for (String reserved : List.of("io.modelcontextprotocol/", "dev.mcp/", "com.mcp.tools/",
                "modelcontextprotocol.io/", "tools.mcp.com/", "api.modelcontextprotocol.org/")) {
            assertTrue(MetaKeys.isReserved(reserved), reserved);
        }
        for (String free : List.of("com.example.mcp/", "example.com/", "mcp/", "com.example/")) {
            assertFalse(MetaKeys.isReserved(free), free);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", "a", "a1", "a-b_c.d", "com.example/key", "com.example/", "a-1.b2/x", "mcp/x",
            "" })
    void validKeys(String key) {
        assertEquals(key, MetaKeys.requireValid(key));
    }

    @ParameterizedTest
    @ValueSource(strings = { "-a", "a-", "a b", "1com/x", "com./x", "com-/x", "/x", "com.example//x", "a/b/c",
            "_x", "io.modelcontextprotocol/progress", "tools.mcp.com/x" })
    void invalidKeys(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MetaKeys.requireValid(key));
        assertTrue(e.getMessage().startsWith("Invalid _meta key '" + key + "': "), e.getMessage());
    }
}
