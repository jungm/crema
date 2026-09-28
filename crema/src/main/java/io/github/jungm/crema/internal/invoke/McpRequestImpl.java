package io.github.jungm.crema.internal.invoke;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.mcpjava.server.ImplementationInfo;
import org.mcpjava.server.McpRequest;

import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.protocol.Mcp;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * The {@link McpRequest} of one JSON-RPC request. There are no sessions, and everything else comes from the
 * request's {@code _meta}.
 */
public final class McpRequestImpl implements McpRequest {

    private final JsonValue id;
    private final JsonObject meta;

    public McpRequestImpl(JsonValue id, JsonObject meta) {
        this.id = id;
        this.meta = meta;
    }

    /**
     * The JSON-RPC id: a {@code String}, a {@code Long}, or a {@code BigDecimal} for numbers that aren't
     * {@code long} integers.
     */
    @Override
    public Object id() {
        return id instanceof JsonString || id instanceof JsonNumber ? Json.toJava(id) : null;
    }

    @Override
    public Optional<String> sessionId() {
        return Optional.empty();
    }

    @Override
    public String protocolVersion() {
        return Json.string(meta, Mcp.META_PROTOCOL_VERSION).orElse("");
    }

    @Override
    public Map<String, Object> rawClientCapabilities() {
        return Json.object(meta, Mcp.META_CLIENT_CAPABILITIES).map(Json::toJavaMap).map(Collections::unmodifiableMap)
                .orElse(Map.of());
    }

    @Override
    public ImplementationInfo clientInfo() {
        return ProtocolJson.implementation(Json.object(meta, Mcp.META_CLIENT_INFO).orElse(null));
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
                if (!key.startsWith(Mcp.META_PREFIX) && !key.equals(Mcp.PROGRESS_TOKEN)) {
                    metadata.put(key, Json.toJava(value));
                }
            });
        }
        return Collections.unmodifiableMap(metadata);
    }
}
