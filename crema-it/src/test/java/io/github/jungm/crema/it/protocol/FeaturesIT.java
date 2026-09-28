package io.github.jungm.crema.it.protocol;

import io.github.jungm.crema.it.mcp.Exchange;
import io.github.jungm.crema.it.mcp.McpClient;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.StringReader;
import java.net.URL;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MCP method against realistic Feature Methods: {@code server/discover}, the four lists, {@code tools/call},
 * {@code resources/read}, {@code prompts/get} and {@code completion/complete}, on two MCP Servers.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class FeaturesIT {

    @ArquillianResource
    private URL base;

    private McpClient mcp;
    private McpClient admin;

    @Deployment(testable = false)
    static WebArchive deployment() {
        return ProtocolWar.create("features");
    }

    @BeforeEach
    void clients() {
        mcp = McpClient.at(base, "mcp");
        admin = McpClient.at(base, "mcp/admin");
    }

    // server/discover

    @Test
    void discoverDescribesTheDefaultMcpServer() {
        JsonObject result = mcp.request("server/discover").result();
        assertEquals(List.of("2026-07-28"), strings(result.getJsonArray("supportedVersions")));
        JsonObject empty = JsonValue.EMPTY_JSON_OBJECT;
        assertEquals(Json.createObjectBuilder().add("tools", empty).add("resources", empty).add("prompts", empty)
                .add("completions", empty).build(), result.getJsonObject("capabilities"));
        assertEquals("Use the shop tools.", result.getString("instructions"));
        assertEquals(300000, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
        assertEquals("complete", result.getString("resultType"));
        JsonObject info = serverInfo(result);
        assertEquals("default", info.getString("name"));
        assertEquals("Protocol IT", info.getString("title"));
        assertEquals("1.2.3", info.getString("version"));
        assertEquals("Crema protocol integration tests", info.getString("description"));
        assertEquals("https://example.com/crema", info.getString("websiteUrl"));
        JsonObject icon = info.getJsonArray("icons").getJsonObject(0);
        assertEquals("https://example.com/icons/server/default.png", icon.getString("src"));
        assertEquals("image/png", icon.getString("mimeType"));
        assertEquals(List.of("48x48"), strings(icon.getJsonArray("sizes")));
        assertEquals("light", icon.getString("theme"));
    }

    @Test
    void discoverDescribesTheAdminMcpServerWithTheManifestVersion() {
        JsonObject result = admin.request("server/discover").result();
        assertFalse(result.containsKey("instructions"));
        JsonObject info = serverInfo(result);
        assertEquals("admin", info.getString("name"));
        assertEquals("Back office", info.getString("title"));
        assertEquals(ProtocolWar.MANIFEST_VERSION, info.getString("version"));
    }

    @Test
    void everyResultCarriesServerInfo() {
        for (String method : List.of("tools/list", "resources/list", "resources/templates/list", "prompts/list")) {
            JsonObject result = mcp.request(method).result();
            assertEquals("default", serverInfo(result).getString("name"), method);
            assertEquals("complete", result.getString("resultType"), method);
            assertEquals(300000, result.getJsonNumber("ttlMs").longValue(), method);
            assertEquals("public", result.getString("cacheScope"), method);
        }
        assertEquals("default", serverInfo(mcp.callTool("health", null).result()).getString("name"));
        assertEquals("admin", serverInfo(admin.callTool("health", null).result()).getString("name"));
    }

    // tools

    @Test
    void toolsAreListedSortedWithSchemasAndMetadata() {
        JsonArray tools = mcp.request("tools/list").result().getJsonArray("tools");
        List<String> names = tools.stream().map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(names.stream().sorted().toList(), names);
        assertTrue(names.containsAll(List.of("add", "greet", "place_order", "health", "countdown", "whoami")), names::toString);
        assertFalse(names.contains("purge"), "admin Tool on the default MCP Server");

        JsonObject add = tool(tools, "add");
        assertEquals("Adds two integers", add.getString("description"));
        JsonObject schema = add.getJsonObject("inputSchema");
        assertEquals("object", schema.getString("type"));
        assertEquals("integer", schema.getJsonObject("properties").getJsonObject("a").getString("type"));
        assertEquals("First summand", schema.getJsonObject("properties").getJsonObject("a").getString("description"));
        assertEquals(List.of("a", "b"), strings(schema.getJsonArray("required")).stream().sorted().toList());

        JsonObject greet = tool(tools, "greet");
        assertEquals("Greeter", greet.getString("title"));
        assertEquals(List.of("name"), strings(greet.getJsonObject("inputSchema").getJsonArray("required")));

        JsonObject noop = tool(tools, "noop");
        assertEquals("object", noop.getJsonObject("inputSchema").getString("type"));

        JsonObject order = tool(tools, "place_order");
        JsonObject orderArg = order.getJsonObject("inputSchema").getJsonObject("properties").getJsonObject("order");
        assertTrue(orderArg.toString().contains("\"items\""), orderArg::toString);
        JsonObject output = order.getJsonObject("outputSchema");
        assertEquals("object", output.getString("type"));
        assertTrue(output.getJsonObject("properties").containsKey("order_id"), output::toString);

        JsonObject stock = tool(tools, "stock");
        assertEquals(Json.createObjectBuilder().add("title", "Stock level").add("readOnlyHint", true)
                .add("destructiveHint", false).add("idempotentHint", true).add("openWorldHint", false).build(),
                stock.getJsonObject("annotations"));
        JsonObject meta = stock.getJsonObject("_meta");
        assertEquals("team-x", meta.getString("com.example/owner"));
        assertEquals(3, meta.getInt("cost"));
        assertEquals(10, meta.getJsonObject("com.example/limits").getInt("max"));
        assertFalse(add.containsKey("annotations"), "annotations only when declared");
    }

    @Test
    void featuresBindToTheirMcpServers() {
        List<String> names = admin.request("tools/list").result().getJsonArray("tools").stream()
                .map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(List.of("health", "purge"), names);
        assertEquals("purged", text(admin.callTool("purge", null).result()));
        assertEquals(-32602, mcp.callTool("purge", null).error(200, -32602).getInt("code"));
        List<String> prompts = admin.request("prompts/list").result().getJsonArray("prompts").stream()
                .map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(List.of("audit"), prompts);
        assertEquals(0, admin.request("resources/list").result().getJsonArray("resources").size());
    }

    @Test
    void toolReturnsPrimitiveAsText() {
        JsonObject result = mcp.callTool("add", Json.createObjectBuilder().add("a", 2).add("b", 3).build()).result();
        assertEquals("5", text(result));
        assertFalse(result.containsKey("isError") && result.getBoolean("isError"));
        assertFalse(result.containsKey("ttlMs"), "tools/call isn't cacheable");
    }

    @Test
    void toolArgumentDefaultApplies() {
        assertEquals("Hello, Ada!", text(mcp.callTool("greet", Json.createObjectBuilder().add("name", "Ada")
                .build()).result()));
        assertEquals("Hi, Ada!", text(mcp.callTool("greet", Json.createObjectBuilder().add("name", "Ada")
                .add("greeting", "Hi").build()).result()));
    }

    @Test
    void recordArgumentAndStructuredContent() {
        JsonObject order = Json.createObjectBuilder().add("customer", "ada").add("items", Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("sku", "apple").add("quantity", 2).add("price", 1.5))
                .add(Json.createObjectBuilder().add("sku", "pear").add("quantity", 1).add("price", 2)))
                .build();
        JsonObject result = mcp.callTool("place_order", Json.createObjectBuilder().add("order", order)
                .add("priority", "HIGH").build()).result();
        JsonObject structured = result.getJsonObject("structuredContent");
        assertTrue(structured.getString("order_id").startsWith("order-"));
        assertEquals(0, structured.getJsonNumber("total").bigDecimalValue().compareTo(new java.math.BigDecimal("5")));
        assertEquals("HIGH", structured.getString("priority"));
        assertEquals(2, structured.getInt("lines"));
        assertEquals(structured, parse(text(result)));
    }

    @Test
    void contentEncoderMostSpecificTypeWins() {
        assertEquals("21.5 degrees Celsius", text(mcp.callTool("temperature",
                Json.createObjectBuilder().add("city", "Graz").build()).result()));
        assertEquals("measurement 40.0", text(mcp.callTool("humidity", null).result()));
    }

    @Test
    void otherReturnValuesBecomeJsonText() {
        assertEquals(Json.createObjectBuilder().add("apple", 3).add("pear", 0).build(),
                parse(text(mcp.callTool("inventory", null).result())));
    }

    @Test
    void toolExceptionsBecomeToolErrors() {
        JsonObject boom = mcp.callTool("boom", null).result();
        assertTrue(boom.getBoolean("isError"));
        assertFalse(boom.toString().contains("secret"), boom::toString);
        JsonObject refuse = mcp.callTool("refuse", null).result();
        assertTrue(refuse.getBoolean("isError"));
        assertEquals("Refusing: the warehouse is closed", text(refuse));
    }

    @Test
    void voidAsyncListAndToolResponseReturns() {
        assertEquals(0, mcp.callTool("noop", null).result().getJsonArray("content").size());
        assertEquals("echo: hi", text(mcp.callTool("echo", Json.createObjectBuilder().add("text", "hi").build())
                .result()));
        JsonArray lines = mcp.callTool("lines", null).result().getJsonArray("content");
        assertEquals(List.of("first", "second"), lines.stream().map(l -> l.asJsonObject().getString("text"))
                .toList());
        JsonObject custom = mcp.callTool("custom", null).result();
        assertEquals("custom", text(custom));
        assertTrue(custom.getJsonObject("structuredContent").getBoolean("ok"));
        assertEquals("abc", custom.getJsonObject("_meta").getString("com.example/trace"));
    }

    @Test
    void contentBlocksOfEveryType() {
        JsonArray content = mcp.callTool("mixed", null).result().getJsonArray("content");
        assertEquals(List.of("text", "image", "audio", "resource_link", "resource"),
                content.stream().map(c -> c.asJsonObject().getString("type")).toList());
        JsonObject text = content.getJsonObject(0);
        assertEquals(List.of("user"), strings(text.getJsonObject("annotations").getJsonArray("audience")));
        JsonObject image = content.getJsonObject(1);
        assertEquals("image/png", image.getString("mimeType"));
        Base64.getDecoder().decode(image.getString("data"));
        assertEquals("audio/wav", content.getJsonObject(2).getString("mimeType"));
        JsonObject link = content.getJsonObject(3);
        assertEquals("app://readme", link.getString("uri"));
        assertEquals("readme", link.getString("name"));
        JsonObject embedded = content.getJsonObject(4).getJsonObject("resource");
        assertEquals("app://embedded", embedded.getString("uri"));
        assertEquals("embedded text", embedded.getString("text"));
        assertEquals("text/plain", embedded.getString("mimeType"));
    }

    @Test
    void optionalArguments() {
        assertEquals("anonymous/-1", text(mcp.callTool("optionals", null).result()));
        assertEquals("ada/36", text(mcp.callTool("optionals", Json.createObjectBuilder().add("nickname", "ada")
                .add("age", 36).build()).result()));
    }

    @Test
    void mcpRequestReflectsTheRequest() {
        Exchange exchange = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "whoami").build())
                .id(Json.createValue("req-7")).meta("com.example/tenant", "acme")
                .meta(McpClient.META_CLIENT_CAPABILITIES, Json.createObjectBuilder()
                        .add("sampling", JsonValue.EMPTY_JSON_OBJECT).build())
                .header("Mcp-Session-Id", "ignored").send();
        JsonObject view = parse(text(exchange.result()));
        assertEquals("req-7", view.getString("id"));
        assertEquals("2026-07-28", view.getString("protocolVersion"));
        assertEquals("crema-it", view.getString("clientName"));
        assertEquals("1.0.0", view.getString("clientVersion"));
        assertFalse(view.getBoolean("sessionId"));
        assertEquals(List.of("com.example/tenant"), strings(view.getJsonArray("metadataKeys")));
        assertEquals(List.of("sampling"), strings(view.getJsonArray("capabilities")));
        assertEquals("req-7", exchange.message().getString("id"));
        assertTrue(exchange.header("Mcp-Session-Id").isEmpty(), "never mints a session");
    }

    @Test
    void missingClientInfoIsFine() {
        JsonObject view = parse(text(mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "whoami")
                .build()).meta(McpClient.META_CLIENT_INFO, (JsonValue) null).send().result()));
        assertEquals("", view.getString("clientName"));
    }

    @Test
    void requestScopeIsFreshPerCall() {
        assertEquals("2", text(mcp.callTool("scoped", null).result()));
        assertEquals("2", text(mcp.callTool("scoped", null).result()));
    }

    @Test
    void badToolArgumentsBecomeToolErrors() {
        JsonObject wrongType = mcp.callTool("add", Json.createObjectBuilder().add("a", "two").add("b", 3).build())
                .result();
        assertTrue(wrongType.getBoolean("isError"), wrongType::toString);
        assertTrue(text(wrongType).contains("a"), wrongType::toString);
        JsonObject missing = mcp.callTool("add", Json.createObjectBuilder().add("a", 1).build()).result();
        assertTrue(missing.getBoolean("isError"), missing::toString);
        assertTrue(text(missing).contains("b"), missing::toString);
        JsonObject badRecord = mcp.callTool("place_order", Json.createObjectBuilder().add("order", "nope").build())
                .result();
        assertTrue(badRecord.getBoolean("isError"), badRecord::toString);
    }

    @Test
    void unknownToolIsInvalidParams() {
        JsonObject error = mcp.callTool("no_such_tool", null).error(200, -32602);
        assertTrue(error.getString("message").contains("no_such_tool"));
    }

    @Test
    void progressIsStreamedAsServerSentEvents() {
        Exchange exchange = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "countdown")
                .add("arguments", Json.createObjectBuilder().add("from", 3)).build())
                .meta("progressToken", "tok-1").send();
        assertEquals(200, exchange.status());
        assertTrue(exchange.isEventStream(), exchange::describe);
        assertEquals("no", exchange.header("X-Accel-Buffering").orElse(null));
        List<JsonObject> messages = exchange.messages();
        assertEquals(4, messages.size(), exchange::describe);
        for (int i = 0; i < 3; i++) {
            JsonObject notification = messages.get(i);
            assertEquals("notifications/progress", notification.getString("method"));
            JsonObject params = notification.getJsonObject("params");
            assertEquals("tok-1", params.getString("progressToken"));
            assertEquals(i + 1, params.getInt("progress"));
            assertEquals(3, params.getInt("total"));
            assertEquals("step " + (i + 1), params.getString("message"));
        }
        assertEquals("lift-off", text(exchange.result()));
    }

    @Test
    void numericProgressTokenKeepsItsType() {
        Exchange exchange = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "countdown")
                .add("arguments", Json.createObjectBuilder().add("from", 1)).build())
                .meta("progressToken", Json.createValue(7)).send();
        JsonObject params = exchange.messages().get(0).getJsonObject("params");
        assertEquals(JsonValue.ValueType.NUMBER, params.get("progressToken").getValueType());
        assertEquals(7, params.getInt("progressToken"));
    }

    @Test
    void withoutProgressTokenTheResponseIsJson() {
        Exchange exchange = mcp.callTool("countdown", Json.createObjectBuilder().add("from", 2).build());
        assertTrue(exchange.contentType().startsWith("application/json"), exchange::describe);
        assertEquals("lift-off", text(exchange.result()));
    }

    // resources

    @Test
    void resourcesAreListedSorted() {
        JsonArray resources = mcp.request("resources/list").result().getJsonArray("resources");
        List<String> names = resources.stream().map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(List.of("broken", "bundle", "config", "gone", "logo", "readme"), names);
        JsonObject readme = resources.getJsonObject(names.indexOf("readme"));
        assertEquals("app://readme", readme.getString("uri"));
        assertEquals("text/markdown", readme.getString("mimeType"));
        assertEquals("The read-me", readme.getString("description"));
        JsonObject annotations = readme.getJsonObject("annotations");
        assertEquals(List.of("user"), strings(annotations.getJsonArray("audience")));
        assertEquals(0.5, annotations.getJsonNumber("priority").doubleValue());
        assertEquals("2026-07-28T10:00:00Z", annotations.getString("lastModified"));
        assertEquals(70, resources.getJsonObject(names.indexOf("logo")).getInt("size"));
        assertEquals("Configuration", resources.getJsonObject(names.indexOf("config")).getString("title"));
    }

    @Test
    void resourceTemplatesAreListed() {
        JsonObject result = mcp.request("resources/templates/list").result();
        JsonArray templates = result.getJsonArray("resourceTemplates");
        assertEquals(List.of("order", "user_file"), templates.stream().map(t -> t.asJsonObject().getString("name"))
                .toList());
        assertEquals("app://orders/{id}", templates.getJsonObject(0).getString("uriTemplate"));
        assertEquals("application/json", templates.getJsonObject(0).getString("mimeType"));
    }

    @Test
    void readTextResource() {
        JsonObject result = mcp.readResource("app://readme").result();
        assertEquals(0, result.getJsonNumber("ttlMs").longValue());
        assertEquals("public", result.getString("cacheScope"));
        JsonObject contents = result.getJsonArray("contents").getJsonObject(0);
        assertEquals("app://readme", contents.getString("uri"));
        assertEquals("text/markdown", contents.getString("mimeType"));
        assertEquals("# Shop", contents.getString("text"));
    }

    @Test
    void readBinaryJsonAndMultiPartResources() {
        JsonObject logo = mcp.readResource("app://logo").result().getJsonArray("contents").getJsonObject(0);
        assertEquals("image/png", logo.getString("mimeType"));
        assertEquals(70, Base64.getDecoder().decode(logo.getString("blob")).length);

        JsonObject config = mcp.readResource("app://config").result().getJsonArray("contents").getJsonObject(0);
        assertEquals("application/json", config.getString("mimeType"));
        assertEquals("eu", parse(config.getString("text")).getString("region"));

        JsonArray bundle = mcp.readResource("app://bundle").result().getJsonArray("contents");
        assertEquals(2, bundle.size());
        assertEquals("text part", bundle.getJsonObject(0).getString("text"));
        assertEquals("blob", new String(Base64.getDecoder().decode(bundle.getJsonObject(1).getString("blob"))));
    }

    @Test
    void readResourceTemplates() {
        JsonObject order = mcp.readResource("app://orders/42").result().getJsonArray("contents").getJsonObject(0);
        assertEquals("app://orders/42", order.getString("uri"));
        assertEquals("application/json", order.getString("mimeType"));
        assertEquals("customer-42", parse(order.getString("text")).getString("customer"));
        assertEquals("ann:notes", mcp.readResource("app://users/ann/files/notes").result().getJsonArray("contents")
                .getJsonObject(0).getString("text"));
    }

    @Test
    void unknownResourceIsNotFoundWithUri() {
        for (String uri : List.of("app://nothing", "app://gone", "app://orders/1/2")) {
            JsonObject error = mcp.readResource(uri).error(200, -32602);
            assertEquals(uri, error.getJsonObject("data").getString("uri"), uri);
        }
    }

    @Test
    void failingResourceIsInternalErrorWithoutDetails() {
        JsonObject error = mcp.readResource("app://broken").error(200, -32603);
        assertFalse(error.toString().contains("secret"), error::toString);
    }

    // prompts

    @Test
    void promptsAreListedWithArguments() {
        JsonArray prompts = mcp.request("prompts/list").result().getJsonArray("prompts");
        List<String> names = prompts.stream().map(t -> t.asJsonObject().getString("name")).toList();
        assertEquals(List.of("briefing", "broken", "conversation", "review"), names);
        JsonObject review = prompts.getJsonObject(3);
        assertEquals("Code review", review.getString("title"));
        JsonArray arguments = review.getJsonArray("arguments");
        assertEquals("code", arguments.getJsonObject(0).getString("name"));
        assertTrue(arguments.getJsonObject(0).getBoolean("required"));
        assertEquals("language", arguments.getJsonObject(1).getString("name"));
        assertEquals("Language", arguments.getJsonObject(1).getString("title"));
        assertFalse(arguments.getJsonObject(1).getBoolean("required"));
    }

    @Test
    void getPrompts() {
        JsonObject review = mcp.getPrompt("review", Json.createObjectBuilder().add("code", "x++").build()).result();
        JsonObject message = review.getJsonArray("messages").getJsonObject(0);
        assertEquals("user", message.getString("role"));
        assertEquals("Review this java code: x++", message.getJsonObject("content").getString("text"));

        JsonArray conversation = mcp.getPrompt("conversation", null).result().getJsonArray("messages");
        assertEquals(List.of("user", "assistant"), conversation.stream()
                .map(m -> m.asJsonObject().getString("role")).toList());

        JsonObject briefing = mcp.getPrompt("briefing", Json.createObjectBuilder().add("topic", "sales").build())
                .result();
        assertEquals("Briefing on sales", briefing.getString("description"));
        JsonObject resource = briefing.getJsonArray("messages").getJsonObject(1).getJsonObject("content");
        assertEquals("resource", resource.getString("type"));
        assertEquals("app://notes/sales", resource.getJsonObject("resource").getString("uri"));
    }

    @Test
    void promptErrors() {
        mcp.getPrompt("nope", null).error(200, -32602);
        mcp.getPrompt("review", null).error(200, -32602);
        mcp.getPrompt("review", Json.createObjectBuilder().add("code", 5).build()).error(200, -32602);
        JsonObject broken = mcp.getPrompt("broken", null).error(200, -32603);
        assertFalse(broken.toString().contains("secret"), broken::toString);
    }

    // completion

    @Test
    void completePromptArgument() {
        JsonObject completion = complete(Json.createObjectBuilder().add("type", "ref/prompt").add("name", "review")
                .build(), "language", "ja", null).result().getJsonObject("completion");
        assertEquals(List.of("java", "javascript"), strings(completion.getJsonArray("values")));
    }

    @Test
    void completeResourceTemplateArgument() {
        JsonObject completion = complete(Json.createObjectBuilder().add("type", "ref/resource")
                .add("uri", "app://orders/{id}").build(), "id", "4", null).result().getJsonObject("completion");
        assertEquals(List.of("41", "42"), strings(completion.getJsonArray("values")));
        assertEquals(2, completion.getInt("total"));
        assertFalse(completion.getBoolean("hasMore"));
    }

    @Test
    void completionIsCappedAtOneHundredAndSeesContext() {
        JsonObject completion = complete(Json.createObjectBuilder().add("type", "ref/resource")
                .add("uri", "app://users/{user}/files/{file}").build(), "file", "f",
                Json.createObjectBuilder().add("user", "ann").build()).result().getJsonObject("completion");
        JsonArray values = completion.getJsonArray("values");
        assertEquals(100, values.size());
        assertEquals("ann-f0", values.getString(0));
        assertTrue(completion.getBoolean("hasMore"));
    }

    @Test
    void completionWithoutCompletionMethodIsEmpty() {
        JsonObject completion = complete(Json.createObjectBuilder().add("type", "ref/prompt").add("name", "review")
                .build(), "code", "x", null).result().getJsonObject("completion");
        assertEquals(0, completion.getJsonArray("values").size());
    }

    @Test
    void completionErrors() {
        complete(Json.createObjectBuilder().add("type", "ref/prompt").add("name", "nope").build(), "a", "b", null)
                .error(200, -32602);
        complete(Json.createObjectBuilder().add("type", "ref/other").add("name", "review").build(), "a", "b", null)
                .error(200, -32602);
    }

    private Exchange complete(JsonObject ref, String argument, String value, JsonObject context) {
        var params = Json.createObjectBuilder().add("ref", ref)
                .add("argument", Json.createObjectBuilder().add("name", argument).add("value", value));
        if (context != null) {
            params.add("context", Json.createObjectBuilder().add("arguments", context));
        }
        return mcp.request("completion/complete", params.build());
    }

    static JsonObject serverInfo(JsonObject result) {
        return result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo");
    }

    static String text(JsonObject result) {
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    static JsonObject tool(JsonArray tools, String name) {
        return tools.stream().map(JsonValue::asJsonObject).filter(t -> t.getString("name").equals(name)).findFirst()
                .orElseThrow();
    }

    static List<String> strings(JsonArray array) {
        return array.getValuesAs(JsonString.class).stream().map(JsonString::getString).toList();
    }

    static JsonObject parse(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
