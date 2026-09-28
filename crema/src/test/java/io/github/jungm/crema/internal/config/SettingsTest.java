package io.github.jungm.crema.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mcpjava.server.McpServer;

import io.github.jungm.crema.McpServerInfo;

class SettingsTest {

    @McpServerInfo(title = "Annotated", version = "1.0", description = "From the annotation",
            instructions = "Use it", websiteUrl = "https://example.com")
    static class DefaultServer {
    }

    @McpServerInfo(name = "admin", title = "Back office")
    static class AdminServer {
    }

    @Test
    void annotationValuesApplyWithoutConfig() {
        ServerSettings settings = ServerSettings.resolve(DefaultServer.class.getAnnotation(McpServerInfo.class),
                ConfigLookup.none(), () -> Optional.of("9.9"));
        assertEquals(new ServerSettings(McpServer.DEFAULT, "Annotated", "1.0", "From the annotation", "Use it",
                "https://example.com", null), settings);
        assertEquals("default", settings.wireName());
    }

    @Test
    void configOverridesAnnotation() {
        ConfigLookup config = MapConfig.of(Map.of(
                "crema.default-server.title", "Configured",
                "crema.default-server.version", "2.0",
                "crema.default-server.website-url", "https://configured.example.com",
                "crema.default-server.resource", "https://mcp.example.com/app/mcp"));
        ServerSettings settings = ServerSettings.resolve(DefaultServer.class.getAnnotation(McpServerInfo.class),
                config, Optional::empty);
        assertEquals("Configured", settings.title());
        assertEquals("2.0", settings.version());
        assertEquals("From the annotation", settings.description());
        assertEquals("https://configured.example.com", settings.websiteUrl());
        assertEquals("https://mcp.example.com/app/mcp", settings.resource());
    }

    @Test
    void namedServersUseTheirOwnKeys() {
        ConfigLookup config = MapConfig.of(Map.of("crema.servers.admin.description", "Configured",
                "crema.default-server.description", "Wrong"));
        ServerSettings settings = ServerSettings.resolve(AdminServer.class.getAnnotation(McpServerInfo.class),
                config, Optional::empty);
        assertEquals("admin", settings.name());
        assertEquals("admin", settings.wireName());
        assertEquals("Back office", settings.title());
        assertEquals("Configured", settings.description());
    }

    @Test
    void versionFallsBackToManifestThenDefault() {
        assertEquals("3.1", ServerSettings.resolve(null, ConfigLookup.none(), () -> Optional.of("3.1")).version());
        ServerSettings settings = ServerSettings.resolve(null, ConfigLookup.none(), Optional::empty);
        assertEquals("0.0.0", settings.version());
        assertNull(settings.title());
        assertNull(settings.instructions());
    }

    @Test
    void blankConfigValuesAreUnset() {
        ServerSettings settings = ServerSettings.resolve(DefaultServer.class.getAnnotation(McpServerInfo.class),
                MapConfig.of(Map.of("crema.default-server.title", " ")), Optional::empty);
        assertEquals("Annotated", settings.title());
    }

    @Test
    void globalSettings() {
        assertEquals(CremaSettings.defaults(), CremaSettings.resolve(ConfigLookup.none()));
        CremaSettings settings = CremaSettings.resolve(MapConfig.of(Map.of(
                "crema.origin.allowed", " https://a.example.com, https://b.example.com ,",
                "crema.cache.list-ttl-ms", "0")));
        assertEquals(List.of("https://a.example.com", "https://b.example.com"), settings.allowedOrigins());
        assertEquals(0, settings.listTtlMs());
        assertEquals(4194304, settings.maxRequestBytes());
        assertEquals(1024, CremaSettings.resolve(MapConfig.of(Map.of("crema.max-request-bytes", " 1024")))
                .maxRequestBytes());
    }

    @Test
    void invalidMaxRequestBytesFails() {
        for (String value : List.of("0", "-1", "lots")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CremaSettings.resolve(MapConfig.of(Map.of("crema.max-request-bytes", value))));
            assertTrue(e.getMessage().contains("crema.max-request-bytes"), e.getMessage());
        }
    }

    @Test
    void invalidListTtlFails() {
        for (String value : List.of("-1", "soon", "1.5")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CremaSettings.resolve(MapConfig.of(Map.of("crema.cache.list-ttl-ms", value))));
            assertTrue(e.getMessage().contains("crema.cache.list-ttl-ms"), e.getMessage());
        }
    }

    @Test
    void withoutMicroProfileConfigImplementationNothingIsConfigured() {
        // The MicroProfile Config API is on the test class path, but no implementation is.
        ConfigLookup config = ConfigLookup.of(getClass().getClassLoader());
        assertEquals(Optional.empty(), config.get("crema.cache.list-ttl-ms"));
    }
}
