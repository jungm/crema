package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.FeatureScanner;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.Scanning;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.protocol.Services;
import io.github.jungm.crema.internal.security.AccessPolicy;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * The MCP methods, from request body and headers to HTTP response, against the {@link Fixtures}.
 */
class McpTransportTest {

    @McpServerInfo(title = "Test Server", version = "1.2.3", instructions = "Use the tools")
    static class TestApp {
    }

    private static final Mapping MAPPING = Mapping.create();
    private static final McpTransport TRANSPORT = transport(CremaSettings.defaults(), AccessPolicy.PERMIT_ALL);
    private static final McpServerModel SERVER = TRANSPORT.server(TestApp.class).orElseThrow();

    @Test
    void discover() {
        JsonObject result = result(call("server/discover", "", null));
        assertEquals(Json.parse("[\"2026-07-28\"]"), result.get("supportedVersions"));
        assertEquals(Json.parse("{\"tools\":{},\"resources\":{},\"prompts\":{},\"completions\":{}}"),
                result.get("capabilities"));
        assertEquals("Use the tools", result.getString("instructions"));
        assertEquals(300000, result.getInt("ttlMs"));
        assertEquals("public", result.getString("cacheScope"));
        assertEquals(Json.parse("{\"name\":\"default\",\"title\":\"Test Server\",\"version\":\"1.2.3\"}"),
                result.getJsonObject("_meta").get("io.modelcontextprotocol/serverInfo"));
    }

    @Test
    void everyResultIsCompleteAndCarriesServerInfo() {
        for (Exchange exchange : List.of(call("tools/list", "", null), call("resources/list", "", null),
                call("resources/templates/list", "", null), call("prompts/list", "", null),
                call("tools/call", ",\"name\":\"nothing\"", "nothing"),
                call("resources/read", ",\"uri\":\"test://static-text\"", "test://static-text"),
                call("prompts/get", ",\"name\":\"test_simple_prompt\"", "test_simple_prompt"),
                call("completion/complete", ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\"test_simple_prompt\"},"
                        + "\"argument\":{\"name\":\"x\",\"value\":\"\"}", null))) {
            JsonObject result = result(exchange);
            assertEquals("complete", result.getString("resultType"), result.toString());
            assertEquals("default", result.getJsonObject("_meta")
                    .getJsonObject("io.modelcontextprotocol/serverInfo").getString("name"));
        }
    }

    @Test
    void listsAreSortedAndCacheable() {
        JsonObject tools = result(call("tools/list", "", null));
        List<String> names = tools.getJsonArray("tools").stream().map(t -> t.asJsonObject().getString("name"))
                .toList();
        assertEquals(names.stream().sorted().toList(), names);
        assertTrue(names.contains("test_simple_text"));
        assertEquals(300000, tools.getInt("ttlMs"));
        assertEquals("public", tools.getString("cacheScope"));
        assertFalse(tools.containsKey("nextCursor"));

        JsonObject resources = result(call("resources/list", "", null));
        assertEquals(List.of("binary", "broken", "text"), resources.getJsonArray("resources").stream()
                .map(r -> r.asJsonObject().getString("name")).toList());
        assertEquals(Json.parse("{\"com.example/owner\":\"team-x\"}"),
                resources.getJsonArray("resources").getJsonObject(2).get("_meta"));
        JsonObject templates = result(call("resources/templates/list", "", null));
        assertEquals(List.of("maybe", "template"), templates.getJsonArray("resourceTemplates").stream()
                .map(r -> r.asJsonObject().getString("name")).toList());
        JsonObject prompts = result(call("prompts/list", "", null));
        assertEquals(4, prompts.getJsonArray("prompts").size());
    }

    @Test
    void anyCursorIsInvalid() {
        assertError(200, -32602, "Invalid cursor", call("tools/list", ",\"cursor\":\"abc\"", null));
        assertEquals("complete", result(call("tools/list", ",\"cursor\":null", null)).getString("resultType"));
    }

    @Test
    void callTools() {
        assertEquals(Json.parse("[{\"type\":\"text\",\"text\":\"This is a simple text response for testing.\"}]"),
                result(call("tools/call", ",\"name\":\"test_simple_text\"", "test_simple_text")).get("content"));
        assertEquals("image/png", result(call("tools/call", ",\"name\":\"test_image_content\"",
                "test_image_content")).getJsonArray("content").getJsonObject(0).getString("mimeType"));
        JsonArray mixed = result(call("tools/call", ",\"name\":\"test_multiple_content_types\"",
                "test_multiple_content_types")).getJsonArray("content");
        assertEquals(List.of("text", "image", "resource"),
                mixed.stream().map(c -> c.asJsonObject().getString("type")).toList());
        assertEquals("7", text(call("tools/call", ",\"name\":\"add\",\"arguments\":{\"a\":6}", "add")));
        assertEquals("5", text(call("tools/call", ",\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}", "add")));
        assertEquals("Hello you", text(call("tools/call", ",\"name\":\"greet\"", "greet")));
        assertEquals("Hello Ann", text(call("tools/call", ",\"name\":\"greet\",\"arguments\":{\"name\":\"Ann\"}",
                "greet")));
        assertEquals(Json.parse("[]"), result(call("tools/call", ",\"name\":\"nothing\"", "nothing"))
                .get("content"));
    }

    @Test
    void structuredContent() {
        JsonObject result = result(call("tools/call", ",\"name\":\"weather\",\"arguments\":{\"city\":\"Graz\"}",
                "weather"));
        assertEquals(Json.parse("{\"temperature\":22.5}"), result.get("structuredContent"));
        assertEquals(Json.parse("{\"temperature\":22.5}"), Json.parse(result.getJsonArray("content")
                .getJsonObject(0).getString("text")));
    }

    @Test
    void mcpRequestParameter() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"method\":\"tools/call\",\"params\":{\"name\":\"whoami\","
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"tester\",\"version\":\"1\"},"
                + "\"com.example/trace\":\"t\"}}}";
        assertEquals("tester r1 {com.example/trace=t}",
                text(post(body, RequestValidatorTest.headers("2026-07-28", "tools/call", "whoami"))));
    }

    @Test
    void toolArgumentErrorsAreToolResults() {
        JsonObject missing = result(call("tools/call", ",\"name\":\"add\",\"arguments\":{}", "add"));
        assertEquals(JsonValue.TRUE, missing.get("isError"));
        assertTrue(missing.getJsonArray("content").getJsonObject(0).getString("text")
                .contains("Missing required argument 'a'"), missing.toString());
        JsonObject invalid = result(call("tools/call", ",\"name\":\"add\",\"arguments\":{\"a\":\"six\"}", "add"));
        assertEquals(JsonValue.TRUE, invalid.get("isError"));
        assertTrue(invalid.getJsonArray("content").getJsonObject(0).getString("text").contains("a: "),
                invalid.toString());
        assertError(200, -32602, "must be an object",
                call("tools/call", ",\"name\":\"add\",\"arguments\":[1]", "add"));
    }

    @Test
    void unknownTool() {
        assertError(200, -32602, "Unknown tool: nope", call("tools/call", ",\"name\":\"nope\"", "nope"));
    }

    @Test
    void failingToolsAreToolResultsThatDontLeak() {
        JsonObject result = result(call("tools/call", ",\"name\":\"test_error_handling\"", "test_error_handling"));
        assertEquals(JsonValue.TRUE, result.get("isError"));
        String text = result.getJsonArray("content").getJsonObject(0).getString("text");
        assertFalse(text.isEmpty());
        assertFalse(text.contains("secret"), text);
        assertEquals("Order 42 doesn't exist",
                text(call("tools/call", ",\"name\":\"visible_error\"", "visible_error")));
    }

    @Test
    void readResources() {
        JsonObject text = result(call("resources/read", ",\"uri\":\"test://static-text\"", "test://static-text"));
        assertEquals(Json.parse("[{\"uri\":\"test://static-text\",\"mimeType\":\"text/plain\","
                + "\"text\":\"This is the content of the static text resource.\"}]"), text.get("contents"));
        assertEquals(0, text.getInt("ttlMs"));
        assertEquals("public", text.getString("cacheScope"));
        assertEquals(Fixtures.PNG, result(call("resources/read", ",\"uri\":\"test://static-binary\"",
                "test://static-binary")).getJsonArray("contents").getJsonObject(0).getString("blob"));
        JsonObject template = result(call("resources/read", ",\"uri\":\"test://template/123/data\"",
                "test://template/123/data")).getJsonArray("contents").getJsonObject(0);
        assertEquals("test://template/123/data", template.getString("uri"));
        assertEquals("application/json", template.getString("mimeType"));
        assertEquals(Json.parse("{\"id\":\"123\",\"templateTest\":true}"), Json.parse(template.getString("text")));
        assertEquals("found", result(call("resources/read", ",\"uri\":\"test://maybe/there\"",
                "test://maybe/there")).getJsonArray("contents").getJsonObject(0).getString("text"));
    }

    @Test
    void resourceNotFound() {
        for (String uri : List.of("test://nonexistent-resource-for-conformance-testing", "test://maybe/gone")) {
            JsonObject error = assertError(200, -32602, "Resource not found",
                    call("resources/read", ",\"uri\":\"" + uri + "\"", uri));
            assertEquals(Json.parse("{\"uri\":\"" + uri + "\"}"), error.get("data"));
        }
    }

    @Test
    void failingResourceIsInternalErrorWithoutDetails() {
        JsonObject error = assertError(200, -32603, "Internal error",
                call("resources/read", ",\"uri\":\"test://broken\"", "test://broken"));
        assertFalse(error.toString().contains("secret"));
    }

    @Test
    void getPrompts() {
        assertEquals(Json.parse("[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Hello\"}}]"),
                result(call("prompts/get", ",\"name\":\"test_simple_prompt\"", "test_simple_prompt"))
                        .get("messages"));
        JsonObject withArgs = result(call("prompts/get", ",\"name\":\"test_prompt_with_arguments\",\"arguments\":"
                + "{\"arg1\":\"testValue1\",\"arg2\":\"testValue2\",\"times\":\"3\"}", "test_prompt_with_arguments"));
        assertEquals("Prompt with arguments: arg1='testValue1', arg2='testValue2' x3", withArgs
                .getJsonArray("messages").getJsonObject(0).getJsonObject("content").getString("text"));
        JsonObject assistant = result(call("prompts/get", ",\"name\":\"assistant\"", "assistant"));
        assertEquals("desc", assistant.getString("description"));
        assertEquals("assistant", assistant.getJsonArray("messages").getJsonObject(0).getString("role"));
    }

    @Test
    void promptErrors() {
        assertError(200, -32602, "Unknown prompt: nope", call("prompts/get", ",\"name\":\"nope\"", "nope"));
        assertError(200, -32602, "Missing required argument 'arg1'", call("prompts/get",
                ",\"name\":\"test_prompt_with_arguments\"", "test_prompt_with_arguments"));
        assertError(200, -32602, "must be strings", call("prompts/get",
                ",\"name\":\"test_prompt_with_arguments\",\"arguments\":{\"arg1\":1}", "test_prompt_with_arguments"));
        assertError(200, -32603, "Visible prompt failure", call("prompts/get", ",\"name\":\"failing\"", "failing"));
    }

    @Test
    void complete() {
        JsonObject completion = result(call("completion/complete", ",\"ref\":{\"type\":\"ref/prompt\","
                + "\"name\":\"test_prompt_with_arguments\"},\"argument\":{\"name\":\"arg1\",\"value\":\"test\"},"
                + "\"context\":{\"arguments\":{\"arg2\":\"x\"}}", null)).getJsonObject("completion");
        assertEquals(100, completion.getJsonArray("values").size());
        assertEquals("xtest0", completion.getJsonArray("values").getString(0));
        assertEquals(JsonValue.TRUE, completion.get("hasMore"));

        JsonObject template = result(call("completion/complete", ",\"ref\":{\"type\":\"ref/resource\","
                + "\"uri\":\"test://template/{id}/data\"},\"argument\":{\"name\":\"id\",\"value\":\"4\"}", null));
        assertEquals(Json.parse("[\"41\",\"42\"]"), template.getJsonObject("completion").get("values"));

        JsonObject none = result(call("completion/complete", ",\"ref\":{\"type\":\"ref/prompt\","
                + "\"name\":\"test_prompt_with_arguments\"},\"argument\":{\"name\":\"arg2\",\"value\":\"\"}", null));
        assertEquals(Json.parse("{\"values\":[],\"hasMore\":false}"), none.get("completion"));
    }

    @Test
    void completionErrors() {
        assertError(200, -32602, "Unknown prompt: nope", call("completion/complete",
                ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\"nope\"},\"argument\":{\"name\":\"a\",\"value\":\"\"}",
                null));
        assertError(200, -32602, "Unknown reference type: ref/tool", call("completion/complete",
                ",\"ref\":{\"type\":\"ref/tool\",\"name\":\"x\"},\"argument\":{\"name\":\"a\",\"value\":\"\"}", null));
        assertError(200, -32602, "Unknown resource template: x://{y}", call("completion/complete",
                ",\"ref\":{\"type\":\"ref/resource\",\"uri\":\"x://{y}\"},\"argument\":{\"name\":\"y\",\"value\":\"\"}",
                null));
        assertError(200, -32602, "argument", call("completion/complete",
                ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\"test_simple_prompt\"}", null));
    }

    @Test
    void progressStreamsWhenRequested() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"test_tool_with_progress\",\"_meta\":{\"progressToken\":\"progress-test-1\","
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        Exchange exchange = post(body, RequestValidatorTest.headers("2026-07-28", "tools/call",
                "test_tool_with_progress"));
        assertTrue(exchange.streamed());
        assertEquals(4, exchange.messages().size());
        for (int i = 0; i < 3; i++) {
            JsonObject notification = exchange.messages().get(i);
            assertEquals("notifications/progress", notification.getString("method"));
            assertEquals(Json.parse("{\"progressToken\":\"progress-test-1\",\"progress\":" + i * 50
                    + ",\"total\":100}"), notification.get("params"));
        }
        JsonObject response = exchange.messages().get(3);
        assertEquals(9, response.getInt("id"));
        assertEquals("done", response.getJsonObject("result").getJsonArray("content").getJsonObject(0)
                .getString("text"));
    }

    @Test
    void progressWithoutTokenRespondsWithJson() {
        Exchange exchange = call("tools/call", ",\"name\":\"test_tool_with_progress\"", "test_tool_with_progress");
        assertFalse(exchange.streamed());
        assertEquals("done", text(exchange));
    }

    @Test
    void tokenWithoutProgressParameterRespondsWithJson() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"test_simple_text\",\"_meta\":{\"progressToken\":7,"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        assertFalse(post(body, RequestValidatorTest.headers("2026-07-28", "tools/call", "test_simple_text"))
                .streamed());
    }

    @Test
    void notificationsGet202WithoutBody() {
        Exchange exchange = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of());
        assertEquals(202, exchange.status());
        assertEquals(List.of(), exchange.messages());
    }

    @Test
    void screen() {
        assertEquals(Optional.empty(), TRANSPORT.screen(SERVER, null, Caller.ANONYMOUS));
        assertEquals(Optional.empty(), TRANSPORT.screen(SERVER, "http://localhost:8080", Caller.ANONYMOUS));
        HttpReply forbidden = TRANSPORT.screen(SERVER, "http://evil.example.com", Caller.ANONYMOUS).orElseThrow();
        assertEquals(403, forbidden.status());
        JsonObject body = (JsonObject) Json.parse(forbidden.body());
        assertFalse(body.containsKey("id"));
        assertEquals("2.0", body.getString("jsonrpc"));
        assertTrue(body.containsKey("error"));

        McpTransport anyOrigin = transport(new CremaSettings(List.of("*"), 0), AccessPolicy.PERMIT_ALL);
        assertEquals(Optional.empty(), anyOrigin.screen(SERVER, "http://evil.example.com", Caller.ANONYMOUS));
    }

    @Test
    void accessPolicyHidesFeaturesAndRejectsCalls() {
        AccessPolicy policy = new AccessPolicy() {
            @Override
            public Optional<Rejection> authenticate(McpServerModel server, Caller caller) {
                return caller.header("Authorization").isEmpty()
                        ? Optional.of(new Rejection(401, Map.of("WWW-Authenticate", "Bearer")))
                        : Optional.empty();
            }

            @Override
            public boolean permits(McpServerModel server, io.github.jungm.crema.internal.model.Feature feature,
                    Caller caller) {
                return !feature.name().equals("add");
            }

            @Override
            public Rejection forbidden(McpServerModel server, Caller caller) {
                return new Rejection(403, Map.of("WWW-Authenticate", "Bearer error=\"insufficient_scope\""));
            }

            @Override
            public boolean isPrivate(McpServerModel server) {
                return true;
            }
        };
        McpTransport secured = transport(CremaSettings.defaults(), policy);
        McpServerModel server = secured.server(TestApp.class).orElseThrow();
        assertEquals(401, secured.screen(server, null, Caller.ANONYMOUS).orElseThrow().status());
        Exchange list = exchange(secured, server, RequestValidatorTest.body(1, "tools/list", "2026-07-28", ""),
                RequestValidatorTest.headers("2026-07-28", "tools/list", null));
        JsonObject result = list.messages().get(0).getJsonObject("result");
        assertEquals("private", result.getString("cacheScope"));
        assertFalse(result.getJsonArray("tools").stream()
                .anyMatch(t -> t.asJsonObject().getString("name").equals("add")));
        Exchange call = exchange(secured, server,
                RequestValidatorTest.body(1, "tools/call", "2026-07-28", ",\"name\":\"add\""),
                RequestValidatorTest.headers("2026-07-28", "tools/call", "add"));
        assertEquals(403, call.status());
        assertEquals(List.of(), call.messages());
        assertEquals("Bearer error=\"insufficient_scope\"", call.headers().get("WWW-Authenticate"));
    }

    record Exchange(int status, Map<String, String> headers, List<JsonObject> messages, boolean streamed) {
    }

    static McpTransport transport(CremaSettings settings, AccessPolicy access) {
        FeatureScanner scanner = new FeatureScanner(MAPPING, IconLookup.reflective());
        Scanning.scan(scanner, Fixtures.Tools.class, new Fixtures.Tools());
        Scanning.scan(scanner, Fixtures.Resources.class, new Fixtures.Resources());
        Scanning.scan(scanner, Fixtures.Prompts.class, new Fixtures.Prompts());
        assertEquals(List.of(), scanner.problems());
        ServerRegistry.Result result = ServerRegistry.build(List.of(new ServerRegistry.Declaration(TestApp.class,
                ServerSettings.resolve(TestApp.class.getAnnotation(McpServerInfo.class), ConfigLookup.none(),
                        Optional::empty),
                List.of())), scanner.features(), settings);
        assertEquals(List.of(), result.problems());
        return new McpTransport(result.registry(),
                new Dispatcher(new Services(MAPPING, ContentEncoders.NONE, access)));
    }

    private static Exchange call(String method, String params, String name) {
        return post(RequestValidatorTest.body(1, method, "2026-07-28", params),
                RequestValidatorTest.headers("2026-07-28", method, name));
    }

    private static Exchange post(String body, Map<String, String> headers) {
        return exchange(TRANSPORT, SERVER, body, headers);
    }

    private static Exchange exchange(McpTransport transport, McpServerModel server, String body,
            Map<String, String> headers) {
        McpTransport.Plan plan = transport.plan(server, body.getBytes(StandardCharsets.UTF_8),
                name -> headers.containsKey(name) ? List.of(headers.get(name)) : List.of(), Caller.ANONYMOUS);
        if (plan instanceof McpTransport.Stream stream) {
            List<JsonObject> events = new ArrayList<>();
            boolean[] closed = {false};
            transport.stream(server, stream.request(), Caller.ANONYMOUS, new McpTransport.EventStream() {
                @Override
                public CompletionStage<?> send(String json) {
                    assertFalse(closed[0]);
                    assertFalse(json.contains("\n"));
                    events.add((JsonObject) Json.parse(json));
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void close() {
                    closed[0] = true;
                }
            });
            assertTrue(closed[0]);
            return new Exchange(200, Map.of(), events, true);
        }
        HttpReply reply = plan instanceof McpTransport.Reply r ? r.reply()
                : transport.respond(server, assertInstanceOf(McpTransport.Respond.class, plan).request(),
                        Caller.ANONYMOUS);
        if (reply.body() != null) {
            assertFalse(reply.body().contains("\n"));
        }
        return new Exchange(reply.status(), reply.headers(),
                reply.body() == null ? List.of() : List.of((JsonObject) Json.parse(reply.body())), false);
    }

    private static JsonObject result(Exchange exchange) {
        assertEquals(200, exchange.status(), exchange.toString());
        JsonObject message = exchange.messages().get(exchange.messages().size() - 1);
        assertNull(message.get("error"), message.toString());
        return message.getJsonObject("result");
    }

    private static String text(Exchange exchange) {
        return result(exchange).getJsonArray("content").getJsonObject(0).getString("text");
    }

    private static JsonObject assertError(int status, int code, String message, Exchange exchange) {
        assertEquals(status, exchange.status(), exchange.toString());
        JsonObject error = exchange.messages().get(0).getJsonObject("error");
        assertEquals(code, error.getInt("code"), error.toString());
        assertTrue(error.getString("message").contains(message), error.toString());
        return error;
    }
}
