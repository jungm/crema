package io.github.jungm.crema.internal.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.mcpjava.server.McpRequest;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.progress.ProgressToken;
import org.mcpjava.server.progress.ProgressTracker;

import io.github.jungm.crema.internal.bind.JsonbBridge;
import io.github.jungm.crema.internal.protocol.Json;
import jakarta.json.JsonObject;

class InjectedParametersTest {

    private static final JsonbBridge JSONB = new JsonbBridge();

    @Test
    void mcpRequestComesFromMeta() {
        JsonObject meta = (JsonObject) Json.parse("""
                {"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                 "io.modelcontextprotocol/clientCapabilities":{"sampling":{}},
                 "io.modelcontextprotocol/clientInfo":{"name":"client","version":"1.0"},
                 "io.modelcontextprotocol/logLevel":"info",
                 "progressToken":5,
                 "traceparent":"00-abc","com.example/n":2}
                """);
        McpRequest request = new McpRequestImpl(Json.parse("7"), meta);
        assertEquals(7L, request.id());
        assertEquals(Optional.empty(), request.sessionId());
        assertEquals("2026-07-28", request.protocolVersion());
        assertEquals(Map.of("sampling", Map.of()), request.rawClientCapabilities());
        assertEquals("client", request.clientInfo().name());
        assertEquals("1.0", request.clientInfo().version());
        assertEquals(Map.of("traceparent", "00-abc", "com.example/n", 2L), request.metadata());
        assertEquals("abc", new McpRequestImpl(Json.parse("\"abc\""), meta).id());
    }

    @Test
    void missingClientInfoIsEmpty() {
        McpRequest request = new McpRequestImpl(Json.parse("1"), Json.object().build());
        assertEquals("", request.clientInfo().name());
        assertEquals("", request.clientInfo().version());
        assertEquals(Map.of(), request.rawClientCapabilities());
    }

    @Test
    void progressNotificationsEchoTheTokenAndMustIncrease() throws Exception {
        List<JsonObject> sent = new ArrayList<>();
        ProgressChannel channel = message -> {
            sent.add(message);
            return CompletableFuture.completedFuture(null);
        };
        Progress progress = new ProgressImpl(ProgressTokenImpl.of(meta("{\"progressToken\":1}")).orElseThrow(),
                channel, JSONB::toJsonValue);
        assertEquals(ProgressToken.Type.INTEGER, progress.token().orElseThrow().type());
        CompletableFuture<Void> first = progress.notificationBuilder().setProgress(50).setTotal(100)
                .setMessage("half").putMetadata("com.example/x", "y").build().send();
        first.get();
        CompletableFuture<Void> same = progress.notificationBuilder().setProgress(50).build().send();
        ExecutionException failure = assertThrows(ExecutionException.class, same::get);
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        progress.notificationBuilder().setProgress(50.5).build().sendAndForget();

        assertEquals(List.of(
                Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{"
                        + "\"progressToken\":1,\"progress\":50,\"total\":100,\"message\":\"half\","
                        + "\"_meta\":{\"com.example/x\":\"y\"}}}"),
                Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{"
                        + "\"progressToken\":1,\"progress\":50.5}}")), sent);
    }

    @Test
    void trackerAdvancesUpToTotal() {
        List<JsonObject> sent = new ArrayList<>();
        Progress progress = new ProgressImpl(ProgressTokenImpl.of(meta("{\"progressToken\":\"t\"}")).orElseThrow(),
                message -> {
                    sent.add(message);
                    return CompletableFuture.completedFuture(null);
                }, JSONB::toJsonValue);
        ProgressTracker tracker = progress.trackerBuilder().setTotal(3).setDefaultStep(2)
                .setMessageBuilder(p -> "at " + p).build();
        tracker.advanceAndForget();
        assertThrows(IllegalStateException.class, () -> tracker.advanceAndForget(2));
        tracker.advanceAndForget(1);
        assertEquals(new BigDecimal(3), tracker.progress());
        assertEquals(List.of("at 2", "at 3"),
                sent.stream().map(m -> m.getJsonObject("params").getString("message")).toList());
        assertEquals("t", sent.get(0).getJsonObject("params").getString("progressToken"));
    }

    @Test
    void withoutTokenSendingDoesNothing() throws Exception {
        Progress progress = new ProgressImpl(null, message -> {
            throw new AssertionError("sent " + message);
        }, JSONB::toJsonValue);
        assertFalse(progress.token().isPresent());
        CompletableFuture<Void> sent = progress.notificationBuilder().setProgress(1).build().send();
        assertTrue(sent.isDone());
        sent.get();
        progress.trackerBuilder().build().advanceAndForget();
        assertThrows(IllegalStateException.class, () -> progress.notificationBuilder().build().token());
    }

    @Test
    void cancellationIsNeverRequested() {
        assertFalse(NeverCancelled.INSTANCE.check().isRequested());
        NeverCancelled.INSTANCE.skipProcessingIfCancelled();
    }

    private static JsonObject meta(String json) {
        return (JsonObject) Json.parse(json);
    }
}
