package io.github.jungm.crema.internal.invoke;

import io.github.jungm.crema.internal.bind.ArgumentBinder;
import io.github.jungm.crema.internal.bind.JsonbBridge;
import io.github.jungm.crema.internal.schema.SchemaGenerator;

/**
 * Crema's own mapping between application values and JSON: JSON-B serialization, Argument binding and JSON Schema
 * generation.
 */
public record Mapping(JsonbBridge jsonb, ArgumentBinder binder, SchemaGenerator schemas) {

    public static Mapping create() {
        JsonbBridge jsonb = new JsonbBridge();
        return new Mapping(jsonb, new ArgumentBinder(jsonb), new SchemaGenerator());
    }
}
