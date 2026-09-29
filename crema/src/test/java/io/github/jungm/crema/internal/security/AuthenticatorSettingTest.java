package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.MapConfig;
import io.github.jungm.crema.internal.config.McpServerSettings;
import jakarta.annotation.security.RolesAllowed;

/**
 * Choosing how an MCP Server authenticates its callers with {@code authenticator}, and the users of Basic
 * authentication.
 */
class AuthenticatorSettingTest {

    @RolesAllowed("user")
    @McpServerInfo(name = "ops")
    static class Protected {
    }

    @McpServerInfo(name = "ops")
    static class Open {
    }

    private final List<String> problems = new ArrayList<>();

    private static ConfigLookup microProfile(Map<String, String> values) {
        ConfigLookup map = MapConfig.of(values);
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

    private AuthenticatorSetting resolve(Class<?> app, ConfigLookup config) {
        McpServerSettings settings = McpServerSettings.resolve(app.getAnnotation(McpServerInfo.class), config,
                Optional::empty);
        return AuthenticatorSetting.resolve(app, settings, config, problems);
    }

    private AuthenticatorSetting resolve(Class<?> app, Map<String, String> values) {
        return resolve(app, microProfile(values));
    }

    private void assertProblem(String expected) {
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(expected), problems.get(0));
    }

    @Test
    void protectedServerNeedsTheSetting() {
        resolve(Protected.class, Map.of("crema.servers.ops.issuer", "https://as.example.com"));
        assertProblem("crema.servers.ops.authenticator isn't set; set it to oauth, basic or bean");
    }

    @Test
    void protectedServerNeedsMicroProfileConfigForTheSetting() {
        resolve(Protected.class, ConfigLookup.none());
        assertProblem("with MicroProfile Config, which isn't available");
    }

    @Test
    void openServerNeedsNoSetting() {
        assertInstanceOf(AuthenticatorSetting.Unset.class, resolve(Open.class, ConfigLookup.none()));
        assertEquals(List.of(), problems);
    }

    @Test
    void oauthNeedsAProtectedServer() {
        resolve(Open.class, Map.of("crema.servers.ops.authenticator", "oauth",
                "crema.servers.ops.issuer", "https://as.example.com"));
        assertProblem("oauth, which needs a protected MCP Server");
    }

    @Test
    void oauth() {
        AuthenticatorSetting setting = resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "oauth",
                "crema.servers.ops.issuer", "https://as.example.com",
                "crema.servers.ops.resource", "https://mcp.example.com/ops"));
        assertEquals(List.of(), problems);
        assertEquals("https://as.example.com",
                ((AuthenticatorSetting.OAuth) setting).protection().issuer());
    }

    @Test
    void bean() {
        assertInstanceOf(AuthenticatorSetting.Bean.class,
                resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "bean")));
        assertInstanceOf(AuthenticatorSetting.Bean.class,
                resolve(Open.class, Map.of("crema.servers.ops.authenticator", "bean")));
        assertEquals(List.of(), problems);
    }

    @Test
    void unknownValuesFail() {
        resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "OAuth"));
        assertProblem("must be oauth, basic or bean, but is 'OAuth'");
    }

    @Test
    void basicUsers() {
        Map<String, String> values = new HashMap<>();
        values.put("crema.servers.ops.authenticator", "basic");
        values.put("crema.servers.ops.users", " foo, bar ,foo");
        values.put("crema.servers.ops.users.foo.password", "123");
        values.put("crema.servers.ops.users.foo.roles", "user, admin,");
        values.put("crema.servers.ops.users.bar.password", " spaced ");
        AuthenticatorSetting setting = resolve(Protected.class, values);
        assertEquals(List.of(), problems);
        assertEquals(Map.of("foo", new BasicMechanism.User("123", Set.of("user", "admin")),
                "bar", new BasicMechanism.User(" spaced ", Set.of())),
                ((AuthenticatorSetting.Basic) setting).users());
    }

    @Test
    void basicWorksOnOpenServers() {
        assertInstanceOf(AuthenticatorSetting.Basic.class, resolve(Open.class, Map.of(
                "crema.servers.ops.authenticator", "basic", "crema.servers.ops.users", "foo",
                "crema.servers.ops.users.foo.password", "123")));
        assertEquals(List.of(), problems);
    }

    @Test
    void basicNeedsUsers() {
        resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "basic"));
        assertProblem("crema.servers.ops.users isn't set");
        problems.clear();
        resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "basic", "crema.servers.ops.users", ","));
        assertProblem("crema.servers.ops.users names no users");
    }

    @Test
    void basicUsersNeedPasswords() {
        resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "basic",
                "crema.servers.ops.users", "foo"));
        assertProblem("crema.servers.ops.users.foo.password isn't set");
    }

    @Test
    void userNamesMustNotContainColons() {
        resolve(Protected.class, Map.of("crema.servers.ops.authenticator", "basic",
                "crema.servers.ops.users", "a:b", "crema.servers.ops.users.a:b.password", "x"));
        assertProblem("user names must not contain ':'");
    }

    @Test
    void passwordsDontAppearInToString() {
        assertTrue(!new BasicMechanism.User("secret", Set.of("user")).toString().contains("secret"));
    }
}
