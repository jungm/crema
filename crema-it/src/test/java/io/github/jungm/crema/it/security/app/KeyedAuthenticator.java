package io.github.jungm.crema.it.security.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.mcpjava.server.McpServer;

import io.github.jungm.crema.McpAuthentication;
import io.github.jungm.crema.McpAuthenticator;
import io.github.jungm.crema.McpCredentials;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Knows the API key {@code user-key} for {@code agent} (role {@code user}) and {@code admin-key} for {@code root}
 * (roles {@code user} and {@code admin}), in the {@code X-Api-Key} header or as a bearer token.
 */
@ApplicationScoped
@McpServer("keyed")
public class KeyedAuthenticator implements McpAuthenticator {

    private static final Map<String, McpAuthentication> KEYS = Map.of(
            "user-key", McpAuthentication.caller("agent", Set.of("user"), Map.of("tenant", "t1")),
            "admin-key", McpAuthentication.caller("root", Set.of("user", "admin")));

    @Override
    public McpAuthentication authenticate(McpCredentials credentials) {
        Optional<String> key = credentials.header("X-Api-Key").or(credentials::bearerToken);
        if (key.isEmpty()) {
            return McpAuthentication.none();
        }
        byte[] presented = key.get().getBytes(StandardCharsets.UTF_8);
        return KEYS.entrySet().stream()
                .filter(entry -> MessageDigest.isEqual(presented, entry.getKey().getBytes(StandardCharsets.UTF_8)))
                .map(Map.Entry::getValue).findFirst().orElse(McpAuthentication.rejected());
    }
}
