package io.github.jungm.crema.internal.model;

import java.lang.reflect.Type;

/**
 * One parameter of a Feature Method or Completion Method: an Injected Parameter or an Argument.
 */
public sealed interface Param permits Param.Injected, Param.Argument {

    /**
     * Parameters that Crema supplies.
     */
    enum Injected implements Param {
        MCP_REQUEST, PROGRESS, CANCELLATION, COMPLETION_CONTEXT
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
