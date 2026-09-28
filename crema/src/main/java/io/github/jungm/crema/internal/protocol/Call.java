package io.github.jungm.crema.internal.protocol;

import java.util.Optional;

import org.mcpjava.server.completion.CompletionContext;

import io.github.jungm.crema.internal.invoke.Invocation;
import io.github.jungm.crema.internal.invoke.McpRequestImpl;
import io.github.jungm.crema.internal.invoke.ProgressChannel;
import io.github.jungm.crema.internal.invoke.ProgressImpl;
import io.github.jungm.crema.internal.invoke.ProgressTokenImpl;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * One request being handled by an MCP Server.
 */
record Call(McpServerModel server, Request request, Caller caller, ProgressChannel progress, Services services) {

    JsonObject params() {
        return request.params();
    }

    /**
     * A required string parameter.
     *
     * @throws McpError {@code -32602} if it is absent or not a string
     */
    String requireString(String name) {
        return Json.string(params(), name)
                .orElseThrow(() -> McpError.invalidParams("Missing required string parameter: " + name));
    }

    /**
     * An optional object parameter.
     *
     * @throws McpError {@code -32602} if it is present but not an object or {@code null}
     */
    Optional<JsonObject> optionalObject(String name) {
        JsonValue value = params().get(name);
        if (value == null || value == JsonValue.NULL) {
            return Optional.empty();
        }
        if (value instanceof JsonObject object) {
            return Optional.of(object);
        }
        throw McpError.invalidParams("Parameter " + name + " must be an object");
    }

    /**
     * Rejects a {@code cursor}: lists aren't paginated, so no cursor is valid.
     */
    void rejectCursor() {
        JsonValue cursor = params().get("cursor");
        if (cursor != null && cursor != JsonValue.NULL) {
            throw McpError.invalidParams("Invalid cursor");
        }
    }

    Invocation invocation(CompletionContext completionContext) {
        JsonObject meta = request.meta();
        return new Invocation(new McpRequestImpl(request.id(), meta),
                new ProgressImpl(ProgressTokenImpl.of(meta).orElse(null), progress,
                        services.mapping().jsonb()::toJsonValue),
                completionContext, caller);
    }
}
