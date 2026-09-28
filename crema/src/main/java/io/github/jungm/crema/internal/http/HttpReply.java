package io.github.jungm.crema.internal.http;

import java.util.Map;

/**
 * An HTTP response to send.
 *
 * @param body compact JSON, or {@code null} for an empty body
 */
public record HttpReply(int status, Map<String, String> headers, String body) {

    public HttpReply {
        headers = Map.copyOf(headers);
    }

    static HttpReply json(int status, String body) {
        return new HttpReply(status, Map.of(), body);
    }
}
