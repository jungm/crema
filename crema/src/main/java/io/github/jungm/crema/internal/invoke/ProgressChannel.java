package io.github.jungm.crema.internal.invoke;

import java.util.concurrent.CompletableFuture;

import jakarta.json.JsonObject;

/**
 * Where the {@code notifications/progress} messages of one request go: the SSE response stream of that request.
 */
@FunctionalInterface
public interface ProgressChannel {

    /**
     * A channel that drops everything, for requests answered with a single JSON response.
     */
    ProgressChannel NONE = message -> CompletableFuture.completedFuture(null);

    /**
     * Sends a JSON-RPC notification. After the final response, sending completes without doing anything.
     */
    CompletableFuture<Void> send(JsonObject notification);
}
