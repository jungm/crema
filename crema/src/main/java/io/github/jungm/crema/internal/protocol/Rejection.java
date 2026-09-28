package io.github.jungm.crema.internal.protocol;

import java.util.Map;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * Ends a request with an HTTP response instead of a JSON-RPC result. It rejects a request before MCP processing
 * ({@code 400}, {@code 401}, {@code 403}, {@code 404}, {@code 413}) or during it ({@code 403} for a Feature the
 * caller may not use). The transport turns it into the response.
 */
public final class Rejection extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Map<String, String> headers;
    private final transient JsonObject message;

    /**
     * @param message the JSON-RPC message to send as the body, or {@code null} for an empty body
     */
    public Rejection(int status, Map<String, String> headers, JsonObject message) {
        super("HTTP " + status, null, false, false);
        this.status = status;
        this.headers = Map.copyOf(headers);
        this.message = message;
    }

    /**
     * A rejection whose body is a JSON-RPC error response, with the error's HTTP status.
     *
     * @param id the request id, or {@code null} if it isn't known
     */
    public static Rejection of(JsonValue id, McpError error) {
        Dispatcher.Response response = Dispatcher.error(id, error);
        return new Rejection(response.status(), Map.of(), response.message());
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /**
     * The body, or {@code null} for an empty body.
     */
    public JsonObject message() {
        return message;
    }
}
