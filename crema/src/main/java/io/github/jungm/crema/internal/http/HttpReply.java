package io.github.jungm.crema.internal.http;

import java.util.HashMap;
import java.util.Map;

import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.protocol.McpError;
import io.github.jungm.crema.internal.protocol.Rejection;

/**
 * An HTTP response to send in one piece.
 *
 * @param body compact JSON, or {@code null} for an empty body
 */
public record HttpReply(int status, Map<String, String> headers, String body) implements McpTransport.Outcome {

    public HttpReply {
        headers = Map.copyOf(headers);
    }

    /**
     * A response without body.
     */
    static HttpReply empty(int status) {
        return new HttpReply(status, Map.of(), null);
    }

    /**
     * A JSON-RPC error response without {@code id}, for a request whose id isn't known or doesn't matter, with the
     * error's HTTP status.
     */
    static HttpReply error(McpError error) {
        return of(Dispatcher.error(null, error));
    }

    static HttpReply of(Dispatcher.Response response) {
        return new HttpReply(response.status(), Map.of(), Json.write(response.message()));
    }

    static HttpReply of(Rejection rejection) {
        return new HttpReply(rejection.status(), rejection.headers(),
                rejection.message() == null ? null : Json.write(rejection.message()));
    }

    HttpReply withHeader(String name, String value) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.put(name, value);
        return new HttpReply(status, copy, body);
    }
}
