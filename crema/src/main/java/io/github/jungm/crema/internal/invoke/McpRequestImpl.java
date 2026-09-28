package io.github.jungm.crema.internal.invoke;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.mcpjava.server.ImplementationInfo;
import org.mcpjava.server.McpRequest;

import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * The {@link McpRequest} of one JSON-RPC request. There are no sessions, and everything else comes from the
 * request's {@code _meta}.
 */
public final class McpRequestImpl implements McpRequest {

    public static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String PROGRESS_TOKEN = "progressToken";
    private static final String RESERVED_PREFIX = "io.modelcontextprotocol/";

    private final JsonValue id;
    private final JsonObject meta;

    public McpRequestImpl(JsonValue id, JsonObject meta) {
        this.id = id;
        this.meta = meta;
    }

    @Override
    public Object id() {
        if (id instanceof JsonString string) {
            return string.getString();
        }
        if (id instanceof JsonNumber number) {
            BigDecimal value = number.bigDecimalValue();
            try {
                return value.longValueExact();
            } catch (ArithmeticException e) {
                return value;
            }
        }
        return null;
    }

    @Override
    public Optional<String> sessionId() {
        return Optional.empty();
    }

    @Override
    public String protocolVersion() {
        return Json.string(meta, PROTOCOL_VERSION).orElse("");
    }

    @Override
    public Map<String, Object> rawClientCapabilities() {
        return Json.object(meta, CLIENT_CAPABILITIES).map(Json::toJavaMap).map(Collections::unmodifiableMap)
                .orElse(Map.of());
    }

    @Override
    public ImplementationInfo clientInfo() {
        return ProtocolJson.implementation(Json.object(meta, CLIENT_INFO).orElse(null));
    }

    /**
     * The request's {@code _meta} without the keys reserved by MCP ({@code io.modelcontextprotocol/*} and
     * {@code progressToken}).
     */
    @Override
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (meta != null) {
            meta.forEach((key, value) -> {
                if (!key.startsWith(RESERVED_PREFIX) && !key.equals(PROGRESS_TOKEN)) {
                    metadata.put(key, Json.toJava(value));
                }
            });
        }
        return Collections.unmodifiableMap(metadata);
    }
}
