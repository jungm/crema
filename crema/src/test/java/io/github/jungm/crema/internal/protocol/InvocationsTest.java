package io.github.jungm.crema.internal.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.invoke.ProgressChannel;
import io.github.jungm.crema.internal.model.FeatureScanner;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.Scanning;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.security.AccessPolicy;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * How {@code tools/call}, {@code resources/read}, {@code prompts/get} and {@code completion/complete} turn what
 * Feature Methods do into results and errors.
 */
class InvocationsTest {

    static class App {
    }

    public static class Features {

        @Tool
        public String toolError() {
            throw new AssertionError("secret detail");
        }

        @Tool
        public CompletionStage<String> asyncToolError() {
            return CompletableFuture.failedFuture(new NoClassDefFoundError("secret detail"));
        }

        @Tool
        public String toolOutOfMemory() {
            throw new OutOfMemoryError("simulated");
        }

        @Tool
        public String echo(String text) {
            return "echo " + text;
        }

        @Tool
        public String maybe(Optional<String> text, JsonValue json) {
            return text.orElse("empty") + " " + json;
        }

        @Resource(uri = "test://error")
        public String resourceError() {
            throw new ExceptionInInitializerError("secret detail");
        }

        @ResourceTemplate(uriTemplate = "file:///docs/{name}")
        public String doc(String name) {
            return "doc " + name;
        }

        @Prompt
        public String promptError() {
            throw new LinkageError("secret detail");
        }
    }

    private static final Mapping MAPPING = Mapping.create();
    private static final Dispatcher DISPATCHER = new Dispatcher(
            new Services(MAPPING, ContentEncoders.NONE, AccessPolicy.PERMIT_ALL));
    private static final McpServerModel SERVER = server(Features.class, new Features());

    @Test
    void errorsFromToolsBecomeToolErrors() {
        for (String tool : List.of("toolError", "asyncToolError")) {
            JsonObject result = result(handle("tools/call", Json.object().add("name", tool)));
            assertTrue(result.getBoolean("isError"), tool);
            String text = result.getJsonArray("content").getJsonObject(0).getString("text");
            assertEquals("Tool " + tool + " failed with an internal error", text);
        }
    }

    @Test
    void virtualMachineErrorsPropagate() {
        assertThrows(OutOfMemoryError.class,
                () -> handle("tools/call", Json.object().add("name", "toolOutOfMemory")));
    }

    @Test
    void errorsFromOtherFeaturesBecomeInternalErrors() {
        assertInternalError(handle("resources/read", Json.object().add("uri", "test://error")));
        assertInternalError(handle("prompts/get", Json.object().add("name", "promptError")));
    }

    @Test
    void templateValuesAreSinglePathSegments() {
        JsonObject result = result(handle("resources/read", Json.object().add("uri", "file:///docs/a%20b")));
        assertEquals("doc a b", result.getJsonArray("contents").getJsonObject(0).getString("text"));
        for (String uri : List.of("file:///docs/..%2F..%2Fetc%2Fpasswd", "file:///docs/..", "file:///docs/%2E")) {
            JsonObject error = handle("resources/read", Json.object().add("uri", uri)).getJsonObject("error");
            assertEquals(-32602, error.getInt("code"), uri);
            assertEquals("Resource not found", error.getString("message"), uri);
        }
    }

    @Test
    void nullForARequiredArgumentIsMissing() {
        JsonObject result = result(handle("tools/call", Json.object().add("name", "echo")
                .add("arguments", Json.object().addNull("text"))));
        assertTrue(result.getBoolean("isError"));
        assertEquals("Invalid arguments for tool echo: Missing required argument 'text'",
                result.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void nullIsAValueOfOptionalAndJsonValue() {
        JsonObject result = result(handle("tools/call", Json.object().add("name", "maybe")
                .add("arguments", Json.object().addNull("text").addNull("json"))));
        assertFalse(result.containsKey("isError") && result.getBoolean("isError"), result::toString);
        assertEquals("empty null", result.getJsonArray("content").getJsonObject(0).getString("text"));
    }

    static McpServerModel server(Class<?> type, Object instance) {
        FeatureScanner scanner = Scanning.scan(new FeatureScanner(MAPPING, IconLookup.reflective()), type,
                instance);
        assertEquals(List.of(), scanner.problems());
        ServerRegistry.Result result = ServerRegistry.build(List.of(new ServerRegistry.Declaration(App.class,
                ServerSettings.resolve(null, ConfigLookup.none(), Optional::empty), List.of())), scanner.features(),
                CremaSettings.defaults());
        assertEquals(List.of(), result.problems());
        return result.registry().server(App.class).orElseThrow();
    }

    static JsonObject handle(String method, jakarta.json.JsonObjectBuilder params) {
        return handle(SERVER, method, params);
    }

    static JsonObject handle(McpServerModel server, String method, jakarta.json.JsonObjectBuilder params) {
        JsonObject meta = Json.object().add("io.modelcontextprotocol/protocolVersion", Dispatcher.PROTOCOL_VERSION)
                .add("io.modelcontextprotocol/clientCapabilities", Json.object()).build();
        Request request = new Request(Json.PROVIDER.createValue(1), method, params.add("_meta", meta).build());
        return DISPATCHER.handle(server, request, Caller.ANONYMOUS, ProgressChannel.NONE).message();
    }

    static JsonObject result(JsonObject message) {
        assertFalse(message.containsKey("error"), message::toString);
        return message.getJsonObject("result");
    }

    static void assertInternalError(JsonObject message) {
        JsonObject error = message.getJsonObject("error");
        assertEquals(-32603, error.getInt("code"), message::toString);
        assertFalse(message.toString().contains("secret detail"), message::toString);
    }
}
