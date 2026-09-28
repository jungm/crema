package io.github.jungm.crema.internal.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import io.github.jungm.crema.internal.invoke.McpRequestImpl;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.protocol.McpError;
import io.github.jungm.crema.internal.protocol.Rejection;
import io.github.jungm.crema.internal.protocol.Request;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Validates the body and headers of a {@code POST} to the MCP Endpoint, in the order the conformance suite
 * requires:
 * <ol>
 * <li>the body is JSON ({@code -32700});
 * <li>it is a single JSON-RPC request or notification ({@code -32600});
 * <li>{@code MCP-Protocol-Version}, {@code Mcp-Method} and, where needed, {@code Mcp-Name} are present and well-formed
 * ({@code -32020});
 * <li>{@code _meta} carries the protocol version and client capabilities ({@code -32602});
 * <li>the headers match the body ({@code -32020});
 * <li>the protocol version is supported ({@code -32022});
 * <li>the method is implemented ({@code 404}, {@code -32601}).
 * </ol>
 * All failures but the last are answered with HTTP {@code 400}.
 */
final class RequestValidator {

    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    static final String METHOD_HEADER = "Mcp-Method";
    static final String NAME_HEADER = "Mcp-Name";

    private static final Set<String> NAMED_METHODS = Set.of("tools/call", "resources/read", "prompts/get");
    private static final String SENTINEL_PREFIX = "=?base64?";
    private static final String SENTINEL_SUFFIX = "?=";

    private RequestValidator() {
    }

    /**
     * @return the valid request, or empty for a JSON-RPC notification, which is accepted and ignored
     * @throws Rejection the JSON-RPC error response to an invalid request
     */
    static Optional<Request> validate(byte[] body, Headers headers) {
        JsonValue json;
        try {
            json = Json.parse(body);
        } catch (JsonException | IllegalStateException | NoSuchElementException e) {
            throw Rejection.of(null, new McpError(McpError.PARSE_ERROR, "Parse error", null, 400));
        }
        if (!(json instanceof JsonObject message)) {
            throw Rejection.of(null, invalidRequest(json.getValueType() == JsonValue.ValueType.ARRAY
                    ? "Batch requests are not supported" : "The body must be a JSON-RPC request object"));
        }
        JsonValue id = message.get("id");
        boolean validId = id instanceof JsonString || id instanceof JsonNumber;
        JsonValue method = message.get("method");
        if (!(message.get("jsonrpc") instanceof JsonString version && version.getString().equals("2.0"))
                || !(method instanceof JsonString)) {
            throw Rejection.of(validId ? id : null, invalidRequest(message.containsKey("result")
                    || message.containsKey("error") ? "JSON-RPC responses must not be sent to the server"
                            : "The body must be a JSON-RPC 2.0 request with a method"));
        }
        if (id == null) {
            return Optional.empty();
        }
        if (!validId) {
            throw Rejection.of(null, invalidRequest("The request id must be a string or a number"));
        }
        String methodName = ((JsonString) method).getString();
        try {
            String protocolVersionHeader = header(headers, PROTOCOL_VERSION_HEADER, methodName);
            String methodHeader = header(headers, METHOD_HEADER, methodName);
            String nameHeader = NAMED_METHODS.contains(methodName) ? header(headers, NAME_HEADER, methodName) : null;

            if (!(message.get("params") instanceof JsonObject params)) {
                throw missingMeta("params");
            }
            if (!(params.get("_meta") instanceof JsonObject meta)) {
                throw missingMeta("_meta");
            }
            if (!(meta.get(McpRequestImpl.PROTOCOL_VERSION) instanceof JsonString protocolVersion)) {
                throw missingMeta("_meta." + McpRequestImpl.PROTOCOL_VERSION);
            }
            if (!(meta.get(McpRequestImpl.CLIENT_CAPABILITIES) instanceof JsonObject)) {
                throw missingMeta("_meta." + McpRequestImpl.CLIENT_CAPABILITIES);
            }

            match(PROTOCOL_VERSION_HEADER, protocolVersionHeader, protocolVersion.getString());
            match(METHOD_HEADER, methodHeader, methodName);
            if (nameHeader != null) {
                String key = methodName.equals("resources/read") ? "uri" : "name";
                if (params.get(key) instanceof JsonString bodyName) {
                    match(NAME_HEADER, nameHeader, bodyName.getString());
                }
            }

            if (!protocolVersion.getString().equals(Dispatcher.PROTOCOL_VERSION)) {
                throw new McpError(McpError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                        Json.object().add("supported", Json.FACTORY.createArrayBuilder()
                                .add(Dispatcher.PROTOCOL_VERSION)).add("requested", protocolVersion.getString())
                                .build(),
                        400);
            }
            if (!Dispatcher.isImplemented(methodName)) {
                throw Dispatcher.methodNotFound(methodName);
            }
            return Optional.of(new Request(id, methodName, params));
        } catch (McpError e) {
            throw Rejection.of(id, e);
        }
    }

    /**
     * Decodes the value of an MCP header field: joins repeated fields with {@code ,} (which Runtimes may split a
     * value at), trims it, rejects characters outside visible ASCII, space and tab, and decodes the Base64 sentinel
     * {@code =?base64?...?=} as UTF-8.
     *
     * @param values the field's values, as {@link Headers#all} returns them
     * @return the value, or {@code null} if the header is absent
     * @throws IllegalArgumentException if the value is malformed
     */
    static String decode(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        String value = String.join(",", values).strip();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7e)) {
                throw new IllegalArgumentException("contains invalid characters");
            }
        }
        if (value.length() >= SENTINEL_PREFIX.length() + SENTINEL_SUFFIX.length()
                && value.startsWith(SENTINEL_PREFIX) && value.endsWith(SENTINEL_SUFFIX)) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(
                        value.substring(SENTINEL_PREFIX.length(), value.length() - SENTINEL_SUFFIX.length()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("isn't valid Base64");
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("isn't valid UTF-8 after Base64 decoding");
            }
        }
        return value;
    }

    private static String header(Headers headers, String name, String method) {
        String value;
        try {
            value = decode(headers.all(name));
        } catch (IllegalArgumentException e) {
            throw headerMismatch("Header " + name + " " + e.getMessage());
        }
        if (value == null) {
            throw headerMismatch(method.equals("initialize")
                    ? "Unsupported: this server speaks MCP " + Dispatcher.PROTOCOL_VERSION + " only (stateless; no "
                            + "initialize)"
                    : "Missing required header: " + name);
        }
        return value;
    }

    private static void match(String header, String headerValue, String bodyValue) {
        if (!headerValue.equals(bodyValue)) {
            throw headerMismatch("Header mismatch: " + header + " header value '" + headerValue
                    + "' does not match body value '" + bodyValue + "'");
        }
    }

    private static McpError headerMismatch(String message) {
        return new McpError(McpError.HEADER_MISMATCH, message, null, 400);
    }

    private static McpError missingMeta(String field) {
        return new McpError(McpError.INVALID_PARAMS, "Missing required field: " + field, null, 400);
    }

    private static McpError invalidRequest(String message) {
        return new McpError(McpError.INVALID_REQUEST, message, null, 400);
    }
}
