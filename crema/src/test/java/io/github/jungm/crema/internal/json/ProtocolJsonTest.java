package io.github.jungm.crema.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mcpjava.server.Icon;
import org.mcpjava.server.ImplementationInfo;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.AudioContent;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.ResourceLink;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.BlobResourceContents;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.TextResourceContents;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.internal.spi.ImplementationInfoImpl;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

/**
 * Compares {@link ProtocolJson} output against the JSON examples of the {@code 2026-07-28} schema.
 */
class ProtocolJsonTest {

    private static final Jsonb JSONB = JsonbBuilder.create();
    private static final Function<Object, JsonValue> ENCODER = value -> json(JSONB.toJson(value));

    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    private static final String WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=";

    @AfterAll
    static void close() throws Exception {
        JSONB.close();
    }

    @Test
    void textContent() {
        assertJson("""
                {"type": "text", "text": "Tool result text"}
                """, ProtocolJson.contentBlock(TextContent.of("Tool result text"), ENCODER));
    }

    @Test
    void imageContentWithAnnotations() {
        ImageContent image = ImageContent.builder(Base64.getDecoder().decode(PNG), "image/png")
                .setAnnotations(Annotations.builder().setAudience(Role.USER).setPriority(0.9).build())
                .build();
        assertJson("""
                {
                  "type": "image",
                  "data": "%s",
                  "mimeType": "image/png",
                  "annotations": {"audience": ["user"], "priority": 0.9}
                }
                """.formatted(PNG), ProtocolJson.contentBlock(image, ENCODER));
    }

    @Test
    void audioContent() {
        assertJson("""
                {"type": "audio", "data": "%s", "mimeType": "audio/wav"}
                """.formatted(WAV),
                ProtocolJson.contentBlock(AudioContent.of(Base64.getDecoder().decode(WAV), "audio/wav"), ENCODER));
    }

    @Test
    void embeddedResourceWithAnnotations() {
        EmbeddedResource embedded = EmbeddedResource
                .builder("fn main() {\n    println!(\"Hello world!\");\n}", "file:///project/src/main.rs")
                .setMimeType("text/x-rust")
                .setAnnotations(Annotations.builder()
                        .setAudience(Role.USER, Role.ASSISTANT)
                        .setPriority(0.7)
                        .setLastModified(Instant.parse("2025-05-03T14:30:00Z"))
                        .build())
                .build();
        assertJson("""
                {
                  "type": "resource",
                  "resource": {
                    "uri": "file:///project/src/main.rs",
                    "mimeType": "text/x-rust",
                    "text": "fn main() {\\n    println!(\\"Hello world!\\");\\n}"
                  },
                  "annotations": {
                    "audience": ["user", "assistant"],
                    "priority": 0.7,
                    "lastModified": "2025-05-03T14:30:00Z"
                  }
                }
                """, ProtocolJson.contentBlock(embedded, ENCODER));
    }

    @Test
    void blobEmbeddedResourceWithMeta() {
        EmbeddedResource embedded = EmbeddedResource.builder(Base64.getDecoder().decode(PNG), "file:///example.png")
                .setMimeType("image/png")
                .putResourceMeta("com.example/inner", 1)
                .putMetadata("com.example/outer", List.of("x"))
                .build();
        assertJson("""
                {
                  "type": "resource",
                  "resource": {
                    "uri": "file:///example.png",
                    "mimeType": "image/png",
                    "blob": "%s",
                    "_meta": {"com.example/inner": 1}
                  },
                  "_meta": {"com.example/outer": ["x"]}
                }
                """.formatted(PNG), ProtocolJson.contentBlock(embedded, ENCODER));
    }

    @Test
    void resourceLink() {
        ResourceLink link = ResourceLink.builder("main.rs", "file:///project/src/main.rs")
                .setDescription("Primary application entry point")
                .setMimeType("text/x-rust")
                .build();
        assertJson("""
                {
                  "type": "resource_link",
                  "uri": "file:///project/src/main.rs",
                  "name": "main.rs",
                  "description": "Primary application entry point",
                  "mimeType": "text/x-rust"
                }
                """, ProtocolJson.contentBlock(link, ENCODER));
    }

    @Test
    void resourceLinkWithAllFields() {
        ResourceLink link = ResourceLink.builder("main.rs", "file:///main.rs")
                .setTitle("Main")
                .setSize(1024)
                .setAnnotations(Annotations.builder().setPriority(1).build())
                .putMetadata("com.example/k", "v")
                .build();
        assertJson("""
                {
                  "type": "resource_link",
                  "uri": "file:///main.rs",
                  "name": "main.rs",
                  "title": "Main",
                  "size": 1024,
                  "annotations": {"priority": 1.0},
                  "_meta": {"com.example/k": "v"}
                }
                """, ProtocolJson.contentBlock(link, ENCODER));
    }

    @Test
    void metadataValuesGoThroughTheEncoder() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("string", "s");
        meta.put("number", 1.5);
        meta.put("bool", true);
        meta.put("null", null);
        meta.put("pojo", new Point(1, 2));
        meta.put("json", Json.createValue("raw"));
        TextContent text = TextContent.builder("t").setMetadata(meta).build();
        assertJson("""
                {
                  "type": "text",
                  "text": "t",
                  "_meta": {
                    "string": "s",
                    "number": 1.5,
                    "bool": true,
                    "null": null,
                    "pojo": {"x": 1, "y": 2},
                    "json": "raw"
                  }
                }
                """, ProtocolJson.contentBlock(text, ENCODER));
    }

    @Test
    void emptyAnnotations() {
        assertJson("{}", ProtocolJson.annotations(Annotations.builder().build()));
    }

    @Test
    void textResourceContents() {
        assertJson("""
                {"uri": "file:///example.txt", "mimeType": "text/plain", "text": "Resource content"}
                """, ProtocolJson.resourceContents(
                TextResourceContents.builder("file:///example.txt", "Resource content").setMimeType("text/plain")
                        .build(),
                ENCODER));
    }

    @Test
    void blobResourceContents() {
        assertJson("""
                {"uri": "file:///example.png", "mimeType": "image/png", "blob": "%s"}
                """.formatted(PNG), ProtocolJson.resourceContents(
                BlobResourceContents.builder("file:///example.png", Base64.getDecoder().decode(PNG))
                        .setMimeType("image/png").build(),
                ENCODER));
    }

    @Test
    void resourceResponseContents() {
        ResourceResponse response = ResourceResponse.of("file:///project/src/main.rs",
                "fn main() {\n    println!(\"Hello world!\");\n}", "text/x-rust");
        assertJson("""
                [
                  {
                    "uri": "file:///project/src/main.rs",
                    "mimeType": "text/x-rust",
                    "text": "fn main() {\\n    println!(\\"Hello world!\\");\\n}"
                  }
                ]
                """, ProtocolJson.resourceContents(response, ENCODER));
    }

    @Test
    void multipleResourceContentsWithoutMimeType() {
        ResourceResponse response = ResourceResponse.builder()
                .addContents(TextResourceContents.of("a", "1"))
                .addContents(BlobResourceContents.builder("b", "hi".getBytes(StandardCharsets.UTF_8))
                        .putMetadata("k", "v").build())
                .build();
        assertJson("""
                [{"uri": "a", "text": "1"}, {"uri": "b", "blob": "aGk=", "_meta": {"k": "v"}}]
                """, ProtocolJson.resourceContents(response, ENCODER));
    }

    @Test
    void toolResultWithUnstructuredText() {
        assertJson("""
                {
                  "content": [
                    {"type": "text", "text": "Current weather in New York:\\nTemperature: 72°F\\nConditions: Partly cloudy"}
                  ],
                  "isError": false
                }
                """, ProtocolJson.toolResult(
                ToolResponse.ofText("Current weather in New York:\nTemperature: 72°F\nConditions: Partly cloudy"),
                ENCODER));
    }

    @Test
    void toolResultWithError() {
        assertJson("""
                {
                  "content": [
                    {"type": "text", "text": "Invalid departure date: must be in the future. Current date is 08/08/2025."}
                  ],
                  "isError": true
                }
                """, ProtocolJson.toolResult(
                ToolResponse.ofError("Invalid departure date: must be in the future. Current date is 08/08/2025."),
                ENCODER));
    }

    @Test
    void toolResultWithStructuredContent() {
        ToolResponse response = ToolResponse.builder()
                .addTextContent("{\"temperature\": 22.5, \"conditions\": \"Partly cloudy\", \"humidity\": 65}")
                .setStructuredContent(new Weather(22.5, "Partly cloudy", 65))
                .build();
        assertJson("""
                {
                  "content": [
                    {"type": "text", "text": "{\\"temperature\\": 22.5, \\"conditions\\": \\"Partly cloudy\\", \\"humidity\\": 65}"}
                  ],
                  "structuredContent": {"temperature": 22.5, "conditions": "Partly cloudy", "humidity": 65},
                  "isError": false
                }
                """, ProtocolJson.toolResult(response, ENCODER));
    }

    @Test
    void toolResultWithArrayStructuredContent() {
        ToolResponse response = ToolResponse.builder()
                .addTextContent("Found 2 users: Alice (alice@example.com) and Bob (bob@example.com).")
                .setStructuredContent(List.of(new User("1", "Alice", "alice@example.com"),
                        new User("2", "Bob", "bob@example.com")))
                .build();
        assertJson("""
                {
                  "content": [
                    {"type": "text", "text": "Found 2 users: Alice (alice@example.com) and Bob (bob@example.com)."}
                  ],
                  "structuredContent": [
                    { "id": "1", "name": "Alice", "email": "alice@example.com" },
                    { "id": "2", "name": "Bob", "email": "bob@example.com" }
                  ],
                  "isError": false
                }
                """, ProtocolJson.toolResult(response, ENCODER));
    }

    @Test
    void structuredOnlyToolResultGetsSerializedTextBlock() {
        JsonObject result = ProtocolJson.toolResult(ToolResponse.ofStructured(Map.of("basket_id", "bsk_a1b2c3")),
                ENCODER);
        JsonObject text = result.getJsonArray("content").getJsonObject(0);
        assertEquals("text", text.getString("type"));
        assertEquals(json("{\"basket_id\": \"bsk_a1b2c3\"}"), json(text.getString("text")));
        assertEquals(json("{\"basket_id\": \"bsk_a1b2c3\"}"), result.get("structuredContent"));
        assertFalse(result.getBoolean("isError"));
    }

    @Test
    void emptyToolResultWithMeta() {
        assertJson("""
                {"content": [], "isError": false, "_meta": {"com.example/k": 1}}
                """, ProtocolJson.toolResult(ToolResponse.builder().putMetadata("com.example/k", 1).build(), ENCODER));
    }

    @Test
    void promptResult() {
        PromptResponse response = PromptResponse.builder()
                .setDescription("Code review prompt")
                .addMessage(Role.USER, TextContent.of("Please review this Python code:\ndef hello():\n    print('world')"))
                .build();
        assertJson("""
                {
                  "description": "Code review prompt",
                  "messages": [
                    {
                      "role": "user",
                      "content": {
                        "type": "text",
                        "text": "Please review this Python code:\\ndef hello():\\n    print('world')"
                      }
                    }
                  ]
                }
                """, ProtocolJson.promptResult(response, ENCODER));
    }

    @Test
    void promptResultWithAssistantMessageAndMeta() {
        PromptResponse response = PromptResponse.builder()
                .addMessage(Role.ASSISTANT, ResourceLink.builder("a", "file:///a").build())
                .putMetadata("k", "v")
                .build();
        assertJson("""
                {
                  "messages": [
                    {"role": "assistant", "content": {"type": "resource_link", "uri": "file:///a", "name": "a"}}
                  ],
                  "_meta": {"k": "v"}
                }
                """, ProtocolJson.promptResult(response, ENCODER));
    }

    @Test
    void completionWithMoreAvailable() {
        assertJson("""
                {"values": ["python", "pytorch", "pyside"], "total": 10, "hasMore": true}
                """, ProtocolJson.completion(CompletionResult.newResult(List.of("python", "pytorch", "pyside"), 10)));
    }

    @Test
    void singleCompletionValue() {
        assertJson("""
                {"values": ["flask"], "total": 1, "hasMore": false}
                """, ProtocolJson.completion(CompletionResult.newCompleteResult(List.of("flask"))));
    }

    @Test
    void completionWithUnknownTotal() {
        assertJson("""
                {"values": []}
                """, ProtocolJson.completion(CompletionResult.builder().build()));
        assertJson("""
                {"values": ["a"], "hasMore": true}
                """, ProtocolJson.completion(CompletionResult.newIncompleteResult(List.of("a"))));
    }

    @Test
    void completionIsTruncatedToOneHundredValues() {
        List<String> values = IntStream.range(0, 150).mapToObj(Integer::toString).toList();
        JsonObject completion = ProtocolJson.completion(CompletionResult.newCompleteResult(values));
        assertEquals(100, completion.getJsonArray("values").size());
        assertEquals("99", completion.getJsonArray("values").getString(99));
        assertEquals(150, completion.getInt("total"));
        assertTrue(completion.getBoolean("hasMore"));

        JsonObject unknown = ProtocolJson.completion(CompletionResult.builder().addValues(values).build());
        assertTrue(unknown.getBoolean("hasMore"));
        assertFalse(unknown.containsKey("total"));

        List<String> exactly100 = values.subList(0, 100);
        assertJson("{\"values\": " + Json.createArrayBuilder(exactly100).build() + ", \"total\": 100, \"hasMore\": false}",
                ProtocolJson.completion(CompletionResult.newCompleteResult(exactly100)));
    }

    @Test
    void icons() {
        assertJson("""
                {"src": "https://example.com/i.png", "mimeType": "image/png"}
                """, ProtocolJson.icon(Icon.of("https://example.com/i.png", "image/png")));
        assertJson("""
                {"src": "i.png", "sizes": ["48x48", "96x96"], "theme": "light"}
                """, ProtocolJson.icon(Icon.builder("i.png").addSize(48, 48).addSize(96, 96)
                .setTheme(Icon.Theme.LIGHT).build()));
        assertJson("""
                {"src": "i.svg", "mimeType": "image/svg+xml", "sizes": ["any"], "theme": "dark"}
                """, ProtocolJson.icon(Icon.builder("i.svg").setMimeType("image/svg+xml").setAnySize()
                .setTheme(Icon.Theme.DARK).build()));
        assertJson("[]", ProtocolJson.icons(List.of()));
    }

    @Test
    void implementation() {
        assertJson("""
                {"name": "default", "version": "1.0"}
                """, ProtocolJson.implementation(ImplementationInfoImpl.of("default", null, "1.0", null, null, null)));
        assertJson("""
                {
                  "name": "default",
                  "title": "Order Service",
                  "version": "1.0",
                  "description": "Orders",
                  "websiteUrl": "https://example.com",
                  "icons": [{"src": "i.png"}]
                }
                """, ProtocolJson.implementation(ImplementationInfoImpl.of("default", "Order Service", "1.0", "Orders",
                "https://example.com", List.of(Icon.builder("i.png").build()))));
        assertJson("""
                {"name": "", "version": ""}
                """, ProtocolJson.implementation(ImplementationInfoImpl.empty()));
    }

    @Test
    void parseImplementation() {
        ImplementationInfo info = ProtocolJson.implementation(json("""
                {
                  "name": "client",
                  "title": "Client",
                  "version": "2.0",
                  "description": "A client",
                  "websiteUrl": "https://example.com",
                  "icons": [
                    {"src": "a.png", "mimeType": "image/png", "sizes": ["48x48", 3], "theme": "dark"},
                    {"mimeType": "image/png"},
                    "junk",
                    {"src": "b.svg", "theme": "sepia"}
                  ]
                }
                """).asJsonObject());
        assertEquals("client", info.name());
        assertEquals("Client", info.title());
        assertEquals("2.0", info.version());
        assertEquals(Optional.of("A client"), info.description());
        assertEquals(Optional.of("https://example.com"), info.websiteUrl());
        assertEquals(2, info.icons().size());
        Icon a = info.icons().get(0);
        assertEquals("a.png", a.src());
        assertEquals(Optional.of("image/png"), a.mimeType());
        assertEquals(List.of("48x48"), a.sizes());
        assertEquals(Optional.of(Icon.Theme.DARK), a.theme());
        Icon b = info.icons().get(1);
        assertEquals("b.svg", b.src());
        assertTrue(b.theme().isEmpty());
        assertTrue(b.mimeType().isEmpty());
    }

    @Test
    void parseImplementationToleratesMissingFields() {
        ImplementationInfo partial = ProtocolJson.implementation(json("""
                {"name": "client", "version": 3, "icons": "none"}
                """).asJsonObject());
        assertEquals("client", partial.name());
        assertEquals("client", partial.title());
        assertEquals("", partial.version());
        assertTrue(partial.description().isEmpty());
        assertTrue(partial.icons().isEmpty());

        ImplementationInfo empty = ProtocolJson.implementation(JsonValue.EMPTY_JSON_OBJECT);
        assertEquals("", empty.name());
        assertEquals("", empty.title());
        assertEquals("", empty.version());

        ImplementationInfo absent = ProtocolJson.implementation((JsonObject) null);
        assertEquals("", absent.name());
        assertEquals("", absent.title());
        assertEquals("", absent.version());
    }

    @Test
    void implementationRoundTrips() {
        ImplementationInfo info = ImplementationInfoImpl.of("n", "T", "1", "d", "https://example.com",
                List.of(Icon.builder("i.png").setMimeType("image/png").addSize(16, 16).setTheme(Icon.Theme.LIGHT)
                        .build()));
        assertEquals(info, ProtocolJson.implementation(ProtocolJson.implementation(info)));
    }

    private static void assertJson(String expected, JsonValue actual) {
        assertEquals(json(expected), actual);
    }

    private static JsonValue json(String text) {
        try (var reader = Json.createReader(new StringReader(text))) {
            return reader.readValue();
        }
    }

    public static class Point {
        public int x;
        public int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public record Weather(double temperature, String conditions, int humidity) {
    }

    public record User(String id, String name, String email) {
    }
}
