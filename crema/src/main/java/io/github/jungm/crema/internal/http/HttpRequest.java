package io.github.jungm.crema.internal.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * An HTTP request to an MCP Endpoint, as far as the transport looks at it.
 *
 * @param method the HTTP method, such as {@code POST}
 * @param contentLength the {@code Content-Length}, or {@code -1} if unknown
 * @param body the request body, read at most once
 */
public record HttpRequest(String method, Headers headers, long contentLength, InputStream body) {

    /**
     * A {@code POST} with this body.
     */
    public static HttpRequest post(byte[] body, Headers headers) {
        return new HttpRequest("POST", headers, body.length, new ByteArrayInputStream(body));
    }
}
