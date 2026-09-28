package io.github.jungm.crema.internal.spi;

import java.util.LinkedHashMap;
import java.util.Map;

import org.mcpjava.server.MetaCarrier;
import io.github.jungm.crema.internal.json.MetaKeys;

/**
 * Base for builders that collect {@code _meta} entries. Keys are checked with {@link MetaKeys}, so malformed keys
 * and keys with a prefix reserved for MCP are rejected.
 */
abstract class MetaBuilder<THIS extends MetaCarrier.Builder<THIS>> implements MetaCarrier.Builder<THIS> {

    private final Map<String, Object> metadata = new LinkedHashMap<>();

    @Override
    public THIS putMetadata(String key, Object value) {
        metadata.put(MetaKeys.requireValid(key), value);
        return self();
    }

    @Override
    public THIS setMetadata(Map<String, Object> metadata) {
        Map<String, Object> replacement = new LinkedHashMap<>();
        metadata.forEach((key, value) -> replacement.put(MetaKeys.requireValid(key), value));
        this.metadata.clear();
        this.metadata.putAll(replacement);
        return self();
    }

    Map<String, Object> metadata() {
        return Meta.copy(metadata);
    }

    @SuppressWarnings("unchecked")
    private THIS self() {
        return (THIS) this;
    }
}
