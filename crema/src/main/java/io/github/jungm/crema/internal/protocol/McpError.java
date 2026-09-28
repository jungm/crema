package io.github.jungm.crema.internal.protocol;

import jakarta.json.JsonObject;

/**
 * A JSON-RPC error raised while handling a request, with the HTTP status of the response that carries it.
 */
public class McpError extends RuntimeException {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HEADER_MISMATCH = -32020;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private static final long serialVersionUID = 1L;

    private final int code;
    private final transient JsonObject data;
    private final int httpStatus;

    public McpError(int code, String message, JsonObject data, int httpStatus) {
        super(message, null, false, false);
        this.code = code;
        this.data = data;
        this.httpStatus = httpStatus;
    }

    /**
     * {@code -32602} with HTTP status 200.
     */
    public static McpError invalidParams(String message) {
        return new McpError(INVALID_PARAMS, message, null, 200);
    }

    /**
     * {@code -32603} with HTTP status 200.
     */
    public static McpError internal(String message) {
        return new McpError(INTERNAL_ERROR, message, null, 200);
    }

    public int code() {
        return code;
    }

    /**
     * The error's {@code data}, or {@code null}.
     */
    public JsonObject data() {
        return data;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
