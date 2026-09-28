package io.github.jungm.crema.internal.protocol;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * A validated JSON-RPC request: its {@code params} is an object carrying {@code _meta} with the protocol version and
 * client capabilities.
 *
 * @param id a JSON string or number
 */
public record Request(JsonValue id, String method, JsonObject params) {

    public JsonObject meta() {
        return params.getJsonObject("_meta");
    }
}
