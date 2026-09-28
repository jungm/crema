package io.github.jungm.crema.internal.model;

import org.mcpjava.server.FeatureType;

/**
 * A Completion Method for one Argument of a Prompt or Resource Template.
 *
 * @param kind {@link FeatureType#PROMPT} or {@link FeatureType#RESOURCE_TEMPLATE}
 * @param target the name of the Prompt or Resource Template
 * @param argument the name of the completed Argument
 */
public record Completion(FeatureType kind, String target, String argument, ApplicationMethod method) {

    /**
     * Names what the Completion Method completes, for messages, e.g. {@code prompt 'greet', argument 'name'}.
     */
    public String describe() {
        return (kind == FeatureType.PROMPT ? "prompt '" : "resource template '") + target + "', argument '"
                + argument + "'";
    }
}
