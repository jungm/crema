package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpAuthentication;
import io.github.jungm.crema.McpAuthenticator;
import io.github.jungm.crema.McpCaller;
import io.github.jungm.crema.McpCredentials;
import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.TestDeployment;
import io.github.jungm.crema.internal.http.Headers;
import io.github.jungm.crema.internal.http.HttpReply;
import io.github.jungm.crema.internal.http.HttpRequest;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.model.InstanceSource;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.McpServerRegistry;
import io.github.jungm.crema.internal.security.Fixture.Exchange;
import io.github.jungm.crema.internal.security.Fixture.RequestCaller;
import jakarta.annotation.security.RolesAllowed;

/**
 * {@link McpAuthenticator}s on a protected MCP Server ({@code @RolesAllowed("user")}) and on an open one.
 */
class AuthenticatorTest {

    @RolesAllowed("user")
    static class ProtectedApp {
    }

    @McpServerInfo(name = "open")
    static class OpenApp {
    }

    public static class Features {

        @Tool
        public String whoami(McpCaller caller, Principal principal) {
            return caller.getName() + " " + caller.claims() + " " + (principal == caller);
        }

        @Tool
        @RolesAllowed("admin")
        public String admin() {
            return "admin";
        }
    }

    @McpServer("open")
    public static class OpenFeatures {

        @Tool
        public String whoami(McpCaller caller) {
            return caller == null ? "anonymous" : caller.getName() + " " + caller.claims();
        }

        @Tool
        @RolesAllowed("admin")
        public String admin() {
            return "admin";
        }
    }

    /**
     * Knows the key {@code alice-key} for {@code alice} (role {@code user}) and {@code root-key} for {@code root}
     * (roles {@code user} and {@code admin}), in the {@code X-Api-Key} header or as a bearer token.
     */
    static final class ApiKeys implements McpAuthenticator {

        private static final Map<String, McpAuthentication> KEYS = Map.of(
                "alice-key", McpAuthentication.caller("alice", Set.of("user"), Map.of("tenant", "t1")),
                "root-key", McpAuthentication.caller("root", Set.of("user", "admin")));

        final AtomicInteger calls = new AtomicInteger();
        String server;

        @Override
        public McpAuthentication authenticate(McpCredentials credentials) {
            calls.incrementAndGet();
            server = credentials.server();
            return credentials.header("X-Api-Key").or(credentials::bearerToken)
                    .map(key -> KEYS.getOrDefault(key, McpAuthentication.rejected()))
                    .orElse(McpAuthentication.none());
        }
    }

    private static final String NO_CREDENTIALS = "Bearer";
    private static final String INVALID = "Bearer error=\"invalid_token\"";

    private final ApiKeys keys = new ApiKeys();
    private McpAuthenticator authenticator = keys;
    private final AtomicInteger released = new AtomicInteger();
    private CremaAccessPolicy policy;
    private McpTransport transport;
    private McpServerModel protectedServer;
    private McpServerModel openServer;

    @AfterEach
    void close() {
        if (policy != null) {
            policy.close();
        }
    }

    private void deploy() {
        McpServerRegistry registry = TestDeployment.create().application(ProtectedApp.class)
                .application(OpenApp.class).bean(Features.class, new Features())
                .bean(OpenFeatures.class, new OpenFeatures()).registry();
        InstanceSource instances = () -> new InstanceSource.Handle() {
            @Override
            public Object get() {
                return authenticator;
            }

            @Override
            public void close() {
                released.incrementAndGet();
            }
        };
        Authenticator bound = new Authenticator(ApiKeys.class, Set.of(McpServer.DEFAULT, "open"), instances);
        CremaAccessPolicy.Result result = CremaAccessPolicy.create(registry.servers(),
                Map.of(ProtectedApp.class, new BeanMechanism(bound), OpenApp.class, new BeanMechanism(bound)));
        assertEquals(List.of(), result.problems());
        policy = result.policy();
        transport = new McpTransport(registry, TestDeployment.create().dispatcher(policy));
        protectedServer = transport.server(ProtectedApp.class).orElseThrow();
        openServer = transport.server(OpenApp.class).orElseThrow();
    }

    private Exchange callTool(McpServerModel server, RequestCaller caller, String tool) {
        return Fixture.call(transport, server, caller, "tools/call", ",\"name\":\"" + tool + "\"", tool, "");
    }

    private Exchange listTools(McpServerModel server, RequestCaller caller) {
        return Fixture.call(transport, server, caller, "tools/list", "", null, "");
    }

    private static RequestCaller key(String key) {
        return RequestCaller.anonymous().header("X-Api-Key", key);
    }

    private static void assertRejected(String challenge, Exchange exchange) {
        assertEquals(401, exchange.status());
        assertEquals(challenge, exchange.challenge());
        assertNull(exchange.message());
    }

    @Test
    void authenticatedCallerComesFromTheAuthenticator() {
        deploy();
        Exchange exchange = callTool(protectedServer, key("alice-key"), "whoami");
        assertEquals(200, exchange.status(), String.valueOf(exchange.message()));
        assertEquals("alice {tenant=t1} true", exchange.text());
        assertEquals("default", keys.server);
        assertEquals(1, released.get(), "the authenticator instance is released after each request");
    }

    @Test
    void bearerTokensAreCredentialsToo() {
        deploy();
        assertEquals("alice {tenant=t1} true", callTool(protectedServer, RequestCaller.bearer("alice-key"),
                "whoami").text());
    }

    @Test
    void protectedServerWithoutCredentialsChallengesWithoutResourceMetadata() {
        deploy();
        assertRejected(NO_CREDENTIALS, callTool(protectedServer, RequestCaller.anonymous(), "whoami"));
        assertRejected(NO_CREDENTIALS, listTools(protectedServer, RequestCaller.withAuthorization("Basic eDp5")));
    }

    @Test
    void theRuntimeCallerDoesNotCountOnAProtectedServer() {
        deploy();
        RequestCaller runtime = new RequestCaller(() -> "bob", Set.of("user", "admin"));
        assertRejected(NO_CREDENTIALS, callTool(protectedServer, runtime, "whoami"));
    }

    @Test
    void rejectedCredentialsAreInvalid() {
        deploy();
        assertRejected(INVALID, callTool(protectedServer, key("wrong"), "whoami"));
        assertRejected(INVALID, callTool(openServer, key("wrong"), "whoami"));
    }

    @Test
    void everyMethodNeedsCredentials() throws Exception {
        deploy();
        McpTransport.Outcome get = transport.handle(protectedServer, new HttpRequest("GET", Headers.NONE, 0,
                InputStream.nullInputStream()), Caller.ANONYMOUS);
        assertEquals(401, ((HttpReply) get).status());
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-Api-Key", List.of("alice-key"));
        McpTransport.Outcome authenticated = transport.handle(protectedServer, new HttpRequest("GET",
                Headers.of(headers), 0, InputStream.nullInputStream()), Caller.ANONYMOUS);
        assertEquals(405, ((HttpReply) authenticated).status());
    }

    @Test
    void rolesComeFromTheAuthenticator() {
        deploy();
        Exchange forbidden = callTool(protectedServer, key("alice-key"), "admin");
        assertEquals(403, forbidden.status());
        assertNull(forbidden.challenge(), "no insufficient_scope without OAuth");
        assertEquals(Set.of("whoami"), listTools(protectedServer, key("alice-key")).names("tools"));
        assertEquals("admin", callTool(protectedServer, key("root-key"), "admin").text());
        assertEquals(Set.of("whoami", "admin"), listTools(protectedServer, key("root-key")).names("tools"));
        assertEquals("private", listTools(protectedServer, key("root-key")).result().getString("cacheScope"));
    }

    @Test
    void repeatedCredentialsAreRejectedWhateverTheAuthenticatorSays() {
        deploy();
        assertRejected(INVALID, callTool(protectedServer,
                RequestCaller.anonymous().header("X-Api-Key", "alice-key", "root-key"), "whoami"));
        assertRejected(INVALID, callTool(protectedServer,
                RequestCaller.withAuthorization("Bearer alice-key", "Bearer root-key"), "whoami"));
        assertRejected(INVALID, callTool(openServer,
                RequestCaller.anonymous().header("X-Api-Key", "alice-key", "root-key"), "whoami"));
    }

    @Test
    void malformedBearerTokensAreRejected() {
        deploy();
        assertRejected(INVALID, callTool(protectedServer, RequestCaller.bearer("alice-key root-key"), "whoami"));
        assertRejected(INVALID, callTool(protectedServer, RequestCaller.withAuthorization("Bearer "), "whoami"));
    }

    @Test
    void unreadHeadersDontMatter() {
        deploy();
        RequestCaller caller = key("alice-key").header("X-Other", "a", "b");
        assertEquals(200, callTool(protectedServer, caller, "whoami").status());
    }

    @Test
    void failingAuthenticatorsReject() {
        authenticator = credentials -> {
            throw new IllegalStateException("database down");
        };
        deploy();
        assertRejected(INVALID, callTool(protectedServer, key("alice-key"), "whoami"));
        assertRejected(INVALID, callTool(openServer, key("alice-key"), "whoami"));
        assertEquals(2, released.get());
        authenticator = credentials -> null;
        assertRejected(INVALID, callTool(protectedServer, key("alice-key"), "whoami"));
    }

    @Test
    void protectedServerWithAnAuthenticatorHasNoResourceMetadata() {
        deploy();
        assertEquals(404, transport.resourceMetadata(protectedServer, "GET").status());
    }

    @Test
    void openServerFallsBackToTheRuntimeCaller() {
        deploy();
        assertEquals("anonymous", callTool(openServer, RequestCaller.anonymous(), "whoami").text());
        RequestCaller runtime = new RequestCaller(() -> "bob", Set.of("admin"));
        assertEquals("bob {}", callTool(openServer, runtime, "whoami").text());
        assertEquals("admin", callTool(openServer, runtime, "admin").text());
        assertEquals(200, callTool(openServer, key("alice-key"), "whoami").status());
        assertEquals("open", keys.server);
    }

    @Test
    void onAnOpenServerTheAuthenticatorsCallerWins() {
        deploy();
        RequestCaller both = new RequestCaller(() -> "bob", Set.of("admin")).header("X-Api-Key", "alice-key");
        assertEquals("alice {tenant=t1}", callTool(openServer, both, "whoami").text());
        assertEquals(403, callTool(openServer, both, "admin").status());
    }

    @Test
    void callerMustHaveAName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpAuthentication.caller("", Set.of()));
        assertTrue(e.getMessage().contains("name"));
    }
}
