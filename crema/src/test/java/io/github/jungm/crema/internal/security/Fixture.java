package io.github.jungm.crema.internal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.mcpjava.server.McpServer;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.http.HttpReply;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.FeatureScanner;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.Scanning;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.protocol.Services;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.json.JsonObject;

/**
 * Four MCP Servers in one application, driven through {@link McpTransport} the way the JAX-RS layer drives it:
 * the protected default MCP Server ({@code @RolesAllowed("user")}), the protected {@code other} MCP Server
 * ({@code @RolesAllowed("**")}), the open {@code open} MCP Server with role-restricted Features, and the open
 * {@code plain} MCP Server without any.
 */
final class Fixture {

    static final String ENDPOINT = "https://mcp.test/app/mcp";
    static final String OTHER_ENDPOINT = "https://mcp.test/app/other";
    static final String OPEN_ENDPOINT = "https://mcp.test/app/open";

    static final TokenValidator.Tuning FAST = new TokenValidator.Tuning(Duration.ofMillis(1000),
            Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofSeconds(2), 50_000);

    private static final Mapping MAPPING = Mapping.create();

    @RolesAllowed("user")
    static class ProtectedApp {
    }

    @McpServerInfo(name = "other")
    @RolesAllowed("**")
    static class OtherApp {
    }

    @McpServerInfo(name = "open")
    static class OpenApp {
    }

    @McpServerInfo(name = "plain")
    static class PlainApp {
    }

    public static class Features {

        @Tool
        @PermitAll
        public String everyone() {
            return "everyone";
        }

        @Tool
        public String users() {
            return "users";
        }

        @Tool
        @RolesAllowed("admin")
        public String admins() {
            return "admins";
        }

        @Tool
        @RolesAllowed(AccessRule.ANY_AUTHENTICATED)
        public String authenticated() {
            return "authenticated";
        }

        @Tool
        @DenyAll
        public String nobody() {
            return "nobody";
        }

        @Tool
        public String whoami(McpCaller caller, Principal principal) {
            return caller.getName() + " " + caller.claims().get("email") + " " + (principal == caller);
        }

        @Prompt
        @RolesAllowed("admin")
        public String adminPrompt(String topic) {
            return "secret prompt about " + topic;
        }

        @CompletePrompt("adminPrompt")
        public List<String> completeTopic(String topic) {
            return List.of(topic + "1");
        }

        @Resource(uri = "test://admin")
        @RolesAllowed("admin")
        public String adminResource() {
            return "secret resource";
        }
    }

    /**
     * Class-level annotations: {@code admin} unless a method says otherwise.
     */
    @RolesAllowed("admin")
    public static class AdminFeatures {

        @Tool
        public String classLevel() {
            return "class level";
        }

        @Tool
        @PermitAll
        public String methodLevel() {
            return "method level";
        }
    }

    @McpServer("other")
    public static class OtherFeatures {

        @Tool
        public String other() {
            return "other";
        }
    }

    @McpServer("open")
    public static class OpenFeatures {

        @Tool
        public String free() {
            return "free";
        }

        @Tool
        @RolesAllowed("admin")
        public String admin() {
            return "admin";
        }

        @Tool
        @RolesAllowed(AccessRule.ANY_AUTHENTICATED)
        public String authenticated() {
            return "authenticated";
        }

        @Tool
        public String whoami(McpCaller caller, Principal principal) {
            return caller == null ? "anonymous" : caller.getName() + " " + caller.claims() + " " + principal.getName();
        }
    }

    /**
     * An open MCP Server without any role restrictions.
     */
    @McpServer("plain")
    public static class PlainFeatures {

        @Tool
        public String hello() {
            return "hello";
        }

        @Resource(uri = "test://plain")
        public String plain() {
            return "plain";
        }
    }

    final McpTransport transport;
    final CremaAccessPolicy policy;
    final McpServerModel protectedServer;
    final McpServerModel otherServer;
    final McpServerModel openServer;
    final McpServerModel plainServer;

    Fixture(Protection defaultProtection, Protection otherProtection) {
        FeatureScanner scanner = new FeatureScanner(MAPPING, IconLookup.reflective());
        Scanning.scan(scanner, Features.class, new Features());
        Scanning.scan(scanner, AdminFeatures.class, new AdminFeatures());
        Scanning.scan(scanner, OtherFeatures.class, new OtherFeatures());
        Scanning.scan(scanner, OpenFeatures.class, new OpenFeatures());
        Scanning.scan(scanner, PlainFeatures.class, new PlainFeatures());
        assertEquals(List.of(), scanner.problems());
        List<ServerRegistry.Declaration> declarations = new ArrayList<>();
        for (Class<?> app : List.of(ProtectedApp.class, OtherApp.class, OpenApp.class, PlainApp.class)) {
            declarations.add(new ServerRegistry.Declaration(app, ServerSettings.resolve(
                    app.getAnnotation(McpServerInfo.class), ConfigLookup.none(), Optional::empty), List.of()));
        }
        ServerRegistry.Result registry = ServerRegistry.build(declarations, scanner.features(),
                CremaSettings.defaults());
        assertEquals(List.of(), registry.problems());
        Map<Class<?>, Protection> protections = new HashMap<>();
        protections.put(ProtectedApp.class, defaultProtection);
        protections.put(OtherApp.class, otherProtection);
        CremaAccessPolicy.Result result = CremaAccessPolicy.create(registry.registry().servers(), protections, FAST);
        assertEquals(List.of(), result.problems());
        policy = result.policy();
        transport = new McpTransport(registry.registry(),
                new Dispatcher(new Services(MAPPING, ContentEncoders.NONE, policy)));
        protectedServer = transport.server(ProtectedApp.class).orElseThrow();
        otherServer = transport.server(OtherApp.class).orElseThrow();
        openServer = transport.server(OpenApp.class).orElseThrow();
        plainServer = transport.server(PlainApp.class).orElseThrow();
    }

    static Protection protection(FakeAuthorizationServer as, String server, String resource, String rolesClaim,
            boolean discover) {
        return new Protection(server, as.issuer(), discover ? null : java.net.URI.create(as.jwksUri()), resource,
                rolesClaim, "sub", 60);
    }

    /**
     * The HTTP exchange of one MCP request.
     */
    record Exchange(int status, Map<String, String> headers, JsonObject message) {

        String challenge() {
            return headers.get("WWW-Authenticate");
        }

        JsonObject result() {
            return message.getJsonObject("result");
        }

        String text() {
            return result().getJsonArray("content").getJsonObject(0).getString("text");
        }

        Set<String> names(String key) {
            return result().getJsonArray(key).stream().map(v -> v.asJsonObject().getString("name"))
                    .collect(Collectors.toSet());
        }
    }

    Exchange call(McpServerModel server, Caller caller, String method, String params, String name) {
        McpTransport.Screening screening = transport.screen(server, null, caller);
        if (screening instanceof McpTransport.Reply reply) {
            return exchange(reply.reply());
        }
        Caller admitted = ((McpTransport.Admitted) screening).caller();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}" + params + "}}";
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("MCP-Protocol-Version", "2026-07-28");
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        McpTransport.Plan plan = transport.plan(server, body.getBytes(StandardCharsets.UTF_8),
                header -> headers.containsKey(header) ? List.of(headers.get(header)) : List.of(), admitted);
        if (plan instanceof McpTransport.Reply reply) {
            return exchange(reply.reply());
        }
        return exchange(transport.respond(server, ((McpTransport.Respond) plan).request(), admitted));
    }

    Exchange callTool(McpServerModel server, Caller caller, String tool) {
        return call(server, caller, "tools/call", ",\"name\":\"" + tool + "\"", tool);
    }

    Exchange listTools(McpServerModel server, Caller caller) {
        return call(server, caller, "tools/list", "", null);
    }

    private static Exchange exchange(HttpReply reply) {
        return new Exchange(reply.status(), reply.headers(),
                reply.body() == null ? null : (JsonObject) Json.parse(reply.body()));
    }

    /**
     * A request as the Runtime sees it.
     */
    static final class RequestCaller implements Caller {

        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Principal principal;
        private final Set<String> roles;

        RequestCaller(Principal principal, Set<String> roles) {
            this.principal = principal;
            this.roles = roles;
        }

        static RequestCaller anonymous() {
            return new RequestCaller(null, Set.of());
        }

        static RequestCaller withAuthorization(String... authorization) {
            RequestCaller caller = anonymous();
            caller.headers.put("Authorization", List.of(authorization));
            return caller;
        }

        static RequestCaller bearer(String token) {
            return withAuthorization("Bearer " + token);
        }

        @Override
        public List<String> header(String name) {
            return headers.getOrDefault(name, List.of());
        }

        @Override
        public Principal principal() {
            return principal;
        }

        @Override
        public boolean isUserInRole(String role) {
            return roles.contains(role);
        }
    }
}
