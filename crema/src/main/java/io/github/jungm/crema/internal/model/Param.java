package io.github.jungm.crema.internal.model;

import java.lang.reflect.Type;

/**
 * One parameter of a Feature Method or Completion Method: an Injected Parameter or an Argument.
 */
public sealed interface Param permits Param.Injected, Param.Argument {

    /**
     * Parameters that Crema supplies. {@code CALLER} is an {@code io.github.jungm.crema.McpCaller} and
     * {@code PRINCIPAL} a {@code java.security.Principal}.
     */
    enum Injected implements Param {
        MCP_REQUEST, PROGRESS, CANCELLATION, COMPLETION_CONTEXT, CALLER, PRINCIPAL
    }

    /**
     * A client-supplied Argument.
     *
     * @param defaultValue the {@code defaultValue} string, or {@code null} when there is none
     * @param title the title, or {@code null}
     * @param description the description, or {@code null}
     */
    record Argument(String name, Type type, boolean required, String defaultValue, String title,
            String description) implements Param {
    }
}
