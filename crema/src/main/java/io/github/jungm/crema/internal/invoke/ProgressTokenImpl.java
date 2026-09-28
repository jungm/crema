package io.github.jungm.crema.internal.invoke;

import java.util.Optional;

import org.mcpjava.server.progress.ProgressToken;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import io.github.jungm.crema.internal.protocol.Mcp;

/**
 * A request's {@code progressToken}, kept as the original JSON value so it is echoed with its JSON type.
 */
public record ProgressTokenImpl(JsonValue json) implements ProgressToken {

    /**
     * The token of a request's {@code _meta}, if it has a string or number {@code progressToken}.
     */
    public static Optional<ProgressTokenImpl> of(JsonObject meta) {
        JsonValue token = meta == null ? null : meta.get(Mcp.PROGRESS_TOKEN);
        return token instanceof JsonString || token instanceof JsonNumber ? Optional.of(new ProgressTokenImpl(token))
                : Optional.empty();
    }

    @Override
    public Type type() {
        return json instanceof JsonNumber ? Type.INTEGER : Type.STRING;
    }

    @Override
    public Number asInteger() {
        if (json instanceof JsonNumber number) {
            return number.isIntegral() ? (Number) number.bigIntegerValue() : number.bigDecimalValue();
        }
        throw new IllegalArgumentException("The progress token " + json + " isn't a number");
    }

    @Override
    public String asString() {
        return json instanceof JsonString string ? string.getString() : json.toString();
    }

    @Override
    public String toString() {
        return asString();
    }
}
