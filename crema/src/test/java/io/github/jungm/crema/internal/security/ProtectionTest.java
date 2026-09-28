package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.ServerSettings;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

/**
 * Detecting protected MCP Servers and resolving their configuration.
 */
class ProtectionTest {

    @RolesAllowed("user")
    static class Protected {
    }

    @DenyAll
    @McpServerInfo(name = "admin")
    static class DenyAllApp {
    }

    @PermitAll
    static class PermitAllApp {
    }

    static class Plain {
    }

    @RolesAllowed("user")
    abstract static class ProtectedBase {
    }

    static class Inherits extends ProtectedBase {
    }

    @RolesAllowed("user")
    @PermitAll
    static class Conflicting {
    }

    private final List<String> problems = new ArrayList<>();

    private static final String RESOURCE = "https://mcp.example.com/app/mcp";

    /**
     * MicroProfile Config with these values, plus {@link #RESOURCE} as the default MCP Server's resource unless
     * they set one.
     */
    private static ConfigLookup microProfile(Map<String, String> values) {
        Map<String, String> withResource = new HashMap<>(values);
        withResource.putIfAbsent("crema.default-server.resource", RESOURCE);
        return bareMicroProfile(withResource);
    }

    private static ConfigLookup bareMicroProfile(Map<String, String> values) {
        ConfigLookup map = ConfigLookup.of(values);
        return new ConfigLookup() {
            @Override
            public Optional<String> get(String key) {
                return map.get(key);
            }

            @Override
            public boolean isMicroProfile() {
                return true;
            }
        };
    }

    private Optional<Protection> resolve(Class<?> app, ConfigLookup config) {
        ServerSettings settings = ServerSettings.resolve(app.getAnnotation(McpServerInfo.class), config,
                Optional::empty);
        return Protection.resolve(app, settings, config, problems);
    }

    @Test
    void defaults() {
        Protection protection = resolve(Protected.class,
                microProfile(Map.of("crema.default-server.issuer", "https://as.example.com/realms/x"))).orElseThrow();
        assertEquals(List.of(), problems);
        assertEquals(new Protection("default", "https://as.example.com/realms/x", null, RESOURCE, "groups", "sub",
                60), protection);
        assertEquals(RESOURCE + "/.well-known/oauth-protected-resource", protection.resourceMetadataUrl());
    }

    @Test
    void protectedServerNeedsAResource() {
        assertEquals(Optional.empty(), resolve(Protected.class,
                bareMicroProfile(Map.of("crema.default-server.issuer", "https://as.example.com"))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("crema.default-server.resource isn't set"), problems.get(0));
        assertTrue(problems.get(0).contains(Protected.class.getName()), problems.get(0));
    }

    @Test
    void resourceMustBeAnAbsoluteHttpUrlWithoutQuery() {
        for (String resource : List.of("urn:mcp", "/app/mcp", "https://mcp.example.com/mcp?x=1",
                "https://mcp.example.com/mcp#f", "https://user@mcp.example.com/mcp", "ftp://mcp.example.com/mcp",
                "  ")) {
            problems.clear();
            assertEquals(Optional.empty(), resolve(Protected.class, microProfile(Map.of(
                    "crema.default-server.issuer", "https://as.example.com",
                    "crema.default-server.resource", resource))), resource);
            assertEquals(1, problems.size(), resource);
        }
    }

    @Test
    void metadataUrlIgnoresTrailingSlashesOfTheResource() {
        assertEquals("https://mcp.example.com/mcp/.well-known/oauth-protected-resource",
                new Protection("default", "https://as.example.com", null, "https://mcp.example.com/mcp/", "groups",
                        "sub", 60).resourceMetadataUrl());
    }

    @Test
    void everySetting() {
        Map<String, String> values = new HashMap<>();
        values.put("crema.servers.admin.issuer", "https://as.example.com");
        values.put("crema.servers.admin.jwks-uri", "https://as.example.com/keys");
        values.put("crema.servers.admin.resource", "https://mcp.example.com/admin");
        values.put("crema.servers.admin.roles-claim", "realm_access.roles");
        values.put("crema.servers.admin.principal-claim", "preferred_username");
        values.put("crema.servers.admin.clock-skew-seconds", "5");
        assertEquals(Optional.of(new Protection("admin", "https://as.example.com",
                URI.create("https://as.example.com/keys"), "https://mcp.example.com/admin", "realm_access.roles",
                "preferred_username", 5)), resolve(DenyAllApp.class, microProfile(values)));
        assertEquals(List.of(), problems);
    }

    @Test
    void onlyRolesAllowedOrDenyAllProtect() {
        ConfigLookup config = microProfile(Map.of("crema.default-server.issuer", "https://as.example.com"));
        assertEquals(Optional.empty(), resolve(Plain.class, config));
        assertEquals(Optional.empty(), resolve(PermitAllApp.class, config));
        assertTrue(resolve(Inherits.class, config).isPresent());
        assertEquals(List.of(), problems);
    }

    @Test
    void protectedServerNeedsMicroProfileConfig() {
        assertEquals(Optional.empty(), resolve(Protected.class,
                ConfigLookup.of(Map.of("crema.default-server.issuer", "https://as.example.com"))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("MicroProfile Config isn't available"), problems.get(0));
        assertTrue(problems.get(0).contains(Protected.class.getName()), problems.get(0));
    }

    @Test
    void protectedServerNeedsAnIssuer() {
        assertEquals(Optional.empty(), resolve(DenyAllApp.class,
                microProfile(Map.of("crema.servers.admin.resource", RESOURCE))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("crema.servers.admin.issuer isn't set"), problems.get(0));
    }

    @Test
    void urlsMustBeHttpsUnlessLoopback() {
        assertTrue(resolve(Protected.class, microProfile(Map.of("crema.default-server.issuer",
                "http://localhost:8180/realms/x"))).isPresent());
        assertTrue(resolve(Protected.class, microProfile(Map.of("crema.default-server.issuer",
                "http://127.0.0.1:8180"))).isPresent());
        assertEquals(List.of(), problems);
        for (String issuer : List.of("http://as.example.com", "as.example.com", "https://as.example.com?x=1",
                "ftp://as.example.com", "https://user@as.example.com")) {
            problems.clear();
            assertEquals(Optional.empty(), resolve(Protected.class,
                    microProfile(Map.of("crema.default-server.issuer", issuer))), issuer);
            assertEquals(1, problems.size(), issuer);
        }
        problems.clear();
        assertEquals(Optional.empty(), resolve(Protected.class, microProfile(Map.of(
                "crema.default-server.issuer", "https://as.example.com",
                "crema.default-server.jwks-uri", "http://as.example.com/keys"))));
        assertEquals(1, problems.size());
    }

    @Test
    void invalidResourceAndClockSkew() {
        assertEquals(Optional.empty(), resolve(Protected.class, microProfile(Map.of(
                "crema.default-server.issuer", "https://as.example.com",
                "crema.default-server.resource", "urn:mcp",
                "crema.default-server.clock-skew-seconds", "-1"))));
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void conflictingAnnotationsFailDeployment() {
        resolve(Conflicting.class, microProfile(Map.of("crema.default-server.issuer", "https://as.example.com")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("more than one of @DenyAll, @PermitAll and "
                + "@RolesAllowed")), problems.toString());
    }

    @Test
    void loopbackHosts() {
        for (String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "[::1]")) {
            assertTrue(Protection.isLoopback(host), host);
        }
        for (String host : List.of("localhost.example.com", "128.0.0.1", "[::2]", "10.0.0.1")) {
            assertEquals(false, Protection.isLoopback(host), host);
        }
    }
}
