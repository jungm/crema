package io.github.jungm.crema.internal.schema;

import java.lang.reflect.Type;

/**
 * One Argument of a Tool's {@code inputSchema}.
 *
 * @param name the Argument name
 * @param type the generic type of the parameter the Argument binds to
 * @param description the description, or {@code null}
 * @param required whether the Argument is listed in {@code required}
 */
public record SchemaProperty(String name, Type type, String description, boolean required) {
}
