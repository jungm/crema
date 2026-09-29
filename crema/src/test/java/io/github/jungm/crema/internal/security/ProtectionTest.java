package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.MapConfig;
import io.github.jungm.crema.internal.config.McpServerSettings;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

/**
 * Detecting protected MCP Servers and resolving their OAuth configuration.
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
     * they set one; the MCP Servers use OAuth.
     */
    private static ConfigLookup microProfile(Map<String, String> values) {
        Map<String, String> withResource = new HashMap<>(values);
        withResource.putIfAbsent("crema.default-server.resource", RESOURCE);
        return bareMicroProfile(withResource);
    }

    private static ConfigLookup bareMicroProfile(Map<String, String> values) {
        Map<String, String> withOAuth = new HashMap<>(values);
        withOAuth.putIfAbsent("crema.default-server.authenticator", "oauth");
        withOAuth.putIfAbsent("crema.servers.admin.authenticator", "oauth");
        ConfigLookup map = MapConfig.of(withOAuth);
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
        McpServerSettings settings = McpServerSettings.resolve(app.getAnnotation(McpServerInfo.class), config,
                Optional::empty);
        return AuthenticatorSetting.resolve(app, settings, config, problems) instanceof AuthenticatorSetting.OAuth oauth
                ? Optional.of(oauth.protection()) : Optional.empty();
    }

    @Test
    void defaults() {
        Protection protection = resolve(Protected.class,
                microProfile(Map.of("crema.default-server.issuer", "https://as.example.com/realms/x"))).orElseThrow();
        assertEquals(List.of(), problems);
        assertEquals(new Protection("default", "https://as.example.com/realms/x", null, RESOURCE, "groups", "sub",
                60, List.of()), protection);
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
                        "sub", 60, List.of()).resourceMetadataUrl());
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
        values.put("crema.servers.admin.scopes", "openid, mcp:tools");
        assertEquals(Optional.of(new Protection("admin", "https://as.example.com",
                URI.create("https://as.example.com/keys"), "https://mcp.example.com/admin", "realm_access.roles",
                "preferred_username", 5, List.of("openid", "mcp:tools"))),
                resolve(DenyAllApp.class, microProfile(values)));
        assertEquals(List.of(), problems);
    }

    @Test
    void scopesIgnoreEmptyEntriesAndDuplicates() {
        assertEquals(List.of("crema", "openid"), resolve(Protected.class, microProfile(Map.of(
                "crema.default-server.issuer", "https://as.example.com",
                "crema.default-server.scopes", " crema,,openid , crema, "))).orElseThrow().scopes());
        assertEquals(List.of(), problems);
    }

    @Test
    void scopesMustBeScopeTokens() {
        for (String scopes : List.of("openid profile", "a\"b", "a\\b", "caf\u00e9")) {
            problems.clear();
            assertEquals(Optional.empty(), resolve(Protected.class, microProfile(Map.of(
                    "crema.default-server.issuer", "https://as.example.com",
                    "crema.default-server.scopes", scopes))), scopes);
            assertEquals(1, problems.size(), scopes);
            assertTrue(problems.get(0).contains("crema.default-server.scopes"), problems.get(0));
        }
    }

    @Test
    void inheritedRolesAllowedProtects() {
        assertTrue(resolve(Inherits.class, microProfile(Map.of("crema.default-server.issuer",
                "https://as.example.com"))).isPresent());
        assertEquals(List.of(), problems);
    }

    @Test
    void oauthNeedsAnIssuer() {
        assertEquals(Optional.empty(), resolve(DenyAllApp.class,
                microProfile(Map.of("crema.servers.admin.resource", RESOURCE))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("crema.servers.admin.issuer isn't set"), problems.get(0));
    }

    @Test
    void onlyRolesAllowedOrDenyAllOnTheApplicationProtect() {
        assertTrue(Protection.isProtected(Protected.class));
        assertTrue(Protection.isProtected(DenyAllApp.class));
        assertTrue(Protection.isProtected(Inherits.class));
        assertFalse(Protection.isProtected(Plain.class));
        assertFalse(Protection.isProtected(PermitAllApp.class));
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
        for (String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "127.255.255.255", "[::1]",
                "::1")) {
            assertTrue(Loopback.isHost(host), host);
        }
        for (String host : Arrays.asList("localhost.example.com", "128.0.0.1", "[::2]", "10.0.0.1", "127.0.0.1.nip.io",
                "127.0.0.256", "127.0.0", "127..0.1", "127.0.0.-1", "0127.0.0.1", "", null)) {
            assertEquals(false, Loopback.isHost(host), host);
        }
    }
}
