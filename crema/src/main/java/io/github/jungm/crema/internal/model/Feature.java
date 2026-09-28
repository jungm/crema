package io.github.jungm.crema.internal.model;

import java.util.Optional;

import org.mcpjava.server.FeatureType;

import jakarta.json.JsonObject;

/**
 * A Feature or a completion, backed by an application method.
 */
public sealed interface Feature {

    /**
     * The name under which the Feature is listed; for completions, a description of what they complete.
     */
    String name();

    FeatureMethod method();

    /**
     * A Tool. {@code definition} is its {@code Tool} object in {@code tools/list}.
     */
    record Tool(String name, JsonObject definition, FeatureMethod method, boolean structuredContent)
            implements Feature {
    }

    /**
     * A Resource. {@code definition} is its {@code Resource} object in {@code resources/list}.
     *
     * @param mimeType the declared MIME type, or {@code null}
     */
    record Resource(String name, String uri, String mimeType, JsonObject definition, FeatureMethod method)
            implements Feature {
    }

    /**
     * A Resource Template. {@code definition} is its {@code ResourceTemplate} object in
     * {@code resources/templates/list}.
     *
     * @param mimeType the declared MIME type, or {@code null}
     */
    record ResourceTemplate(String name, UriTemplate uriTemplate, String mimeType, JsonObject definition,
            FeatureMethod method) implements Feature {
    }

    /**
     * A Prompt. {@code definition} is its {@code Prompt} object in {@code prompts/list}.
     */
    record Prompt(String name, JsonObject definition, FeatureMethod method) implements Feature {
    }

    /**
     * A Completion Method for one Argument of a Prompt or Resource Template.
     *
     * @param kind {@link FeatureType#PROMPT} or {@link FeatureType#RESOURCE_TEMPLATE}
     * @param target the name of the Prompt or Resource Template
     * @param argument the name of the completed Argument
     */
    record Completion(FeatureType kind, String target, String argument, FeatureMethod method) implements Feature {

        @Override
        public String name() {
            return (kind == FeatureType.PROMPT ? "prompt '" : "resource template '") + target + "', argument '"
                    + argument + "'";
        }
    }

    /**
     * The {@link FeatureType} of Features; empty for completions.
     */
    default Optional<FeatureType> type() {
        if (this instanceof Tool) {
            return Optional.of(FeatureType.TOOL);
        } else if (this instanceof Resource) {
            return Optional.of(FeatureType.RESOURCE);
        } else if (this instanceof ResourceTemplate) {
            return Optional.of(FeatureType.RESOURCE_TEMPLATE);
        } else if (this instanceof Prompt) {
            return Optional.of(FeatureType.PROMPT);
        }
        return Optional.empty();
    }
}
