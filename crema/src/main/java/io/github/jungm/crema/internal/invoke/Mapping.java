package io.github.jungm.crema.internal.invoke;

import io.github.jungm.crema.internal.bind.ArgumentBinder;
import io.github.jungm.crema.internal.bind.JsonbBridge;
import io.github.jungm.crema.internal.schema.SchemaGenerator;

/**
 * Crema's own mapping between application values and JSON: JSON-B serialization, Argument binding and JSON Schema
 * generation. One per application; {@link #close()} releases it when the application stops.
 */
public record Mapping(JsonbBridge jsonb, ArgumentBinder binder, SchemaGenerator schemas) implements AutoCloseable {

    public static Mapping create() {
        JsonbBridge jsonb = new JsonbBridge();
        return new Mapping(jsonb, new ArgumentBinder(jsonb), new SchemaGenerator());
    }

    /**
     * Closes Crema's {@code Jsonb} instance.
     */
    @Override
    public void close() {
        jsonb.close();
    }
}
