package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.TestDeployment;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.McpServerRegistry;
import io.github.jungm.crema.internal.security.Fixture.Exchange;
import io.github.jungm.crema.internal.security.Fixture.RequestCaller;
import jakarta.annotation.security.RolesAllowed;

/**
 * HTTP Basic authentication on a protected MCP Server ({@code @RolesAllowed("user")}) and on an open one.
 */
class BasicMechanismTest {

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
            return caller == null ? "anonymous" : caller.getName();
        }
    }

    private static final String CHALLENGE = "Basic realm=\"default\", charset=\"UTF-8\"";

    private static final Map<String, BasicMechanism.User> USERS = Map.of(
            "foo", new BasicMechanism.User("123", Set.of("user", "admin")),
            "bar", new BasicMechanism.User("päss:word", Set.of("user")));

    private CremaAccessPolicy policy;
    private McpTransport transport;
    private McpServerModel protectedServer;
    private McpServerModel openServer;

    @BeforeEach
    void deploy() {
        McpServerRegistry registry = TestDeployment.create().application(ProtectedApp.class)
                .application(OpenApp.class).bean(Features.class, new Features())
                .bean(OpenFeatures.class, new OpenFeatures()).registry();
        CremaAccessPolicy.Result result = CremaAccessPolicy.create(registry.servers(), Map.of(
                ProtectedApp.class, new BasicMechanism("default", USERS),
                OpenApp.class, new BasicMechanism("open", USERS)));
        assertEquals(List.of(), result.problems());
        policy = result.policy();
        transport = new McpTransport(registry, TestDeployment.create().dispatcher(policy));
        protectedServer = transport.server(ProtectedApp.class).orElseThrow();
        openServer = transport.server(OpenApp.class).orElseThrow();
    }

    @AfterEach
    void close() {
        policy.close();
    }

    private Exchange callTool(McpServerModel server, RequestCaller caller, String tool) {
        return Fixture.call(transport, server, caller, "tools/call", ",\"name\":\"" + tool + "\"", tool, "");
    }

    private static RequestCaller basic(String credentials) {
        return RequestCaller.withAuthorization("Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertChallenged(RequestCaller caller) {
        Exchange exchange = callTool(protectedServer, caller, "whoami");
        assertEquals(401, exchange.status());
        assertEquals(CHALLENGE, exchange.challenge());
        assertNull(exchange.message());
    }

    @Test
    void validCredentialsAdmitTheUser() {
        Exchange exchange = callTool(protectedServer, basic("foo:123"), "whoami");
        assertEquals(200, exchange.status(), String.valueOf(exchange.message()));
        assertEquals("foo {} true", exchange.text());
        assertEquals("admin", callTool(protectedServer, basic("foo:123"), "admin").text());
    }

    @Test
    void passwordsMayHaveColonsAndNonAsciiCharacters() {
        assertEquals("bar {} true", callTool(protectedServer, basic("bar:päss:word"), "whoami").text());
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertEquals(200, callTool(protectedServer, RequestCaller.withAuthorization("basic "
                + Base64.getEncoder().encodeToString("foo:123".getBytes(StandardCharsets.UTF_8))), "whoami")
                .status());
    }

    @Test
    void rolesComeFromTheConfiguration() {
        Exchange forbidden = callTool(protectedServer, basic("bar:päss:word"), "admin");
        assertEquals(403, forbidden.status());
        assertNull(forbidden.challenge());
    }

    @Test
    void everyFailureGetsTheSameChallenge() {
        assertChallenged(RequestCaller.anonymous());
        assertChallenged(RequestCaller.bearer("abc"));
        assertChallenged(basic("foo:wrong"));
        assertChallenged(basic("foo:"));
        assertChallenged(basic("nobody:123"));
        assertChallenged(basic("foo"));
        assertChallenged(RequestCaller.withAuthorization("Basic"));
        assertChallenged(RequestCaller.withAuthorization("Basic not base64!"));
        assertChallenged(RequestCaller.withAuthorization("Basic "
                + Base64.getEncoder().encodeToString(new byte[] {'f', 'o', 'o', ':', (byte) 0xff})));
        assertChallenged(RequestCaller.withAuthorization("Basic "
                + Base64.getEncoder().encodeToString("foo:123".getBytes(StandardCharsets.UTF_8)), "Basic x"));
    }

    @Test
    void userNamesAreCaseSensitive() {
        assertChallenged(basic("FOO:123"));
    }

    @Test
    void theRuntimeCallerDoesNotCountOnAProtectedServer() {
        assertChallenged(new RequestCaller(() -> "bob", Set.of("user")));
    }

    @Test
    void openServerFallsBackToTheRuntimeCaller() {
        assertEquals("anonymous", callTool(openServer, RequestCaller.anonymous(), "whoami").text());
        assertEquals("bob", callTool(openServer, new RequestCaller(() -> "bob", Set.of()), "whoami").text());
        assertEquals("foo", callTool(openServer, basic("foo:123"), "whoami").text());
        Exchange wrong = callTool(openServer, basic("foo:wrong"), "whoami");
        assertEquals(401, wrong.status());
        assertEquals("Basic realm=\"open\", charset=\"UTF-8\"", wrong.challenge());
    }

    @Test
    void noResourceMetadata() {
        assertEquals(404, transport.resourceMetadata(protectedServer, "GET").status());
    }
}
