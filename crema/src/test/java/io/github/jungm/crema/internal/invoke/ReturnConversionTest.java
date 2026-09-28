package io.github.jungm.crema.internal.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mcpjava.server.ContentEncoder;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.ResourceContents;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.TextResourceContents;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.internal.bind.JsonbBridge;
import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.model.Scanning;
import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonValue;

class ReturnConversionTest {

    private static final JsonbBridge JSONB = new JsonbBridge();
    private static final Function<Object, JsonValue> ENCODER = JSONB::toJsonValue;

    public record Point(int x, int y) {
    }

    public static class Shape {
    }

    public static class Circle extends Shape {
    }

    static class ShapeEncoder implements ContentEncoder<Shape> {
        @Override
        public ContentBlock encode(Shape object) {
            return TextContent.of("shape");
        }

        @Override
        public Class<Shape> getType() {
            return Shape.class;
        }
    }

    static class CircleEncoder implements ContentEncoder<Circle> {
        @Override
        public ContentBlock encode(Circle object) {
            return TextContent.of("circle");
        }

        @Override
        public Class<Circle> getType() {
            return Circle.class;
        }
    }

    private static final ContentEncoders ENCODERS = new ContentEncoders(() -> List.of(
            new ContentEncoders.Candidate(Shape.class, Scanning.instance(new ShapeEncoder())),
            new ContentEncoders.Candidate(null, Scanning.instance(new CircleEncoder()))));

    @Test
    void toolValues() {
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"isError\":false}", "hi", false);
        assertTool("{\"content\":[],\"isError\":false}", null, false);
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}],"
                + "\"isError\":false}", List.of("a", TextContent.of("b")), false);
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"x\\\":1,\\\"y\\\":2}\"}],\"isError\":false}",
                new Point(1, 2), false);
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"[1,2]\"}],\"isError\":false}", List.of(1, 2), false);
        ImageContent image = ImageContent.builder(new byte[] {1}, "image/png").build();
        assertEquals(List.of(image), ReturnConversion.tool(image, false, ENCODERS, JSONB).content());
        ToolResponse response = ToolResponse.ofError("no");
        assertSame(response, ReturnConversion.tool(response, false, ENCODERS, JSONB));
    }

    @Test
    void structuredContent() {
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"x\\\":1,\\\"y\\\":2}\"}],"
                + "\"structuredContent\":{\"x\":1,\"y\":2},\"isError\":false}", new Point(1, 2), true);
    }

    @Test
    void mostSpecificContentEncoderWins() {
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"circle\"}],\"isError\":false}", new Circle(), false);
        assertTool("{\"content\":[{\"type\":\"text\",\"text\":\"shape\"}],\"isError\":false}", new Shape(), false);
        assertEquals(Optional.empty(), ENCODERS.encode("text"));
    }

    @Test
    void resourceValues() {
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"text/plain\",\"text\":\"hi\"}]", "hi", "text/plain");
        assertResource("[{\"uri\":\"x://a\",\"text\":\"hi\"}]", "hi", null);
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"image/png\",\"blob\":\"aGk=\"}]",
                "hi".getBytes(StandardCharsets.UTF_8), "image/png");
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"application/json\",\"text\":\"{\\\"x\\\":1,\\\"y\\\":2}\"}]",
                new Point(1, 2), null);
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"application/vnd.point+json\",\"text\":\"{\\\"x\\\":1,\\\"y\\\":2}\"}]",
                new Point(1, 2), "application/vnd.point+json");
        assertResource("[]", List.of(), returnType("contents"), "text/plain");
        assertResource("[]", List.of(), returnType("textContents"), "text/plain");
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"text/plain\",\"text\":\"[]\"}]", List.of(),
                returnType("points"), "text/plain");
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"application/json\",\"text\":\"[]\"}]", List.of(),
                returnType("raw"), null);
        assertResource("[{\"uri\":\"x://a\",\"mimeType\":\"application/json\",\"text\":\"[]\"}]", List.of(),
                Object.class, null);
        assertResource("[{\"uri\":\"x://other\",\"text\":\"t\"}]", TextResourceContents.of("x://other", "t"), null);
        assertResource("[{\"uri\":\"x://1\",\"text\":\"1\"},{\"uri\":\"x://2\",\"text\":\"2\"}]",
                List.of(TextResourceContents.of("x://1", "1"), TextResourceContents.of("x://2", "2")), null);
        ResourceResponse response = ResourceResponse.of("x://b", "b");
        assertSame(response, ReturnConversion.resource(response, ResourceResponse.class, "x://a", null, JSONB));
    }

    @Test
    void promptValues() {
        assertEquals(Json.parse("{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}]}"),
                ProtocolJson.promptResult(ReturnConversion.prompt("hi"), ENCODER));
        PromptResponse two = ReturnConversion.prompt(List.of(
                PromptResponse.of(Role.USER, TextContent.of("q")).messages().get(0),
                PromptResponse.of(Role.ASSISTANT, TextContent.of("a")).messages().get(0)));
        assertEquals(List.of(Role.USER, Role.ASSISTANT), two.messages().stream().map(m -> m.role()).toList());
        PromptResponse response = PromptResponse.of(Role.USER, TextContent.of("x"));
        assertSame(response, ReturnConversion.prompt(response));
        assertEquals(1, ReturnConversion.prompt(response.messages().get(0)).messages().size());
        assertThrows(IllegalStateException.class, () -> ReturnConversion.prompt(null));
    }

    @Test
    void completionValues() {
        assertEquals(List.of("a"), ReturnConversion.completion("a").values());
        assertEquals(List.of("a", "b"), ReturnConversion.completion(List.of("a", "b")).values());
        assertEquals(List.of(), ReturnConversion.completion(null).values());
        CompletionResult result = CompletionResult.newResult(List.of("x"), 10);
        assertSame(result, ReturnConversion.completion(result));

        List<String> many = new ArrayList<>(IntStream.range(0, 150).mapToObj(Integer::toString).toList());
        var json = ProtocolJson.completion(ReturnConversion.completion(many));
        assertEquals(100, json.getJsonArray("values").size());
        assertEquals(JsonValue.TRUE, json.get("hasMore"));
    }

    private static void assertTool(String expected, Object value, boolean structured) {
        assertEquals(Json.parse(expected),
                ProtocolJson.toolResult(ReturnConversion.tool(value, structured, ENCODERS, JSONB), ENCODER));
    }

    private static void assertResource(String expected, Object value, String mimeType) {
        assertResource(expected, value, value.getClass(), mimeType);
    }

    private static void assertResource(String expected, Object value, Type valueType, String mimeType) {
        assertEquals(Json.parse(expected), ProtocolJson.resourceContents(
                ReturnConversion.resource(value, valueType, "x://a", mimeType, JSONB), ENCODER));
    }

    /**
     * Declared return types of Resource methods.
     */
    @SuppressWarnings("rawtypes")
    interface Signatures {

        List<ResourceContents> contents();

        List<TextResourceContents> textContents();

        List<Point> points();

        List raw();
    }

    private static Type returnType(String method) {
        try {
            return Signatures.class.getMethod(method).getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
