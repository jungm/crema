package io.github.jungm.crema.internal.model;

import jakarta.json.JsonObject;

/**
 * A Feature, backed by its Feature Method.
 */
public sealed interface Feature {

    /**
     * The name under which the Feature is listed.
     */
    String name();

    ApplicationMethod method();

    /**
     * A Tool. {@code definition} is its {@code Tool} object in {@code tools/list}.
     */
    record Tool(String name, JsonObject definition, ApplicationMethod method, boolean structuredContent)
            implements Feature {
    }

    /**
     * A Resource. {@code definition} is its {@code Resource} object in {@code resources/list}.
     *
     * @param mimeType the declared MIME type, or {@code null}
     */
    record Resource(String name, String uri, String mimeType, JsonObject definition, ApplicationMethod method)
            implements Feature {
    }

    /**
     * A Resource Template. {@code definition} is its {@code ResourceTemplate} object in
     * {@code resources/templates/list}.
     *
     * @param mimeType the declared MIME type, or {@code null}
     */
    record ResourceTemplate(String name, UriTemplate uriTemplate, String mimeType, JsonObject definition,
            ApplicationMethod method) implements Feature {
    }

    /**
     * A Prompt. {@code definition} is its {@code Prompt} object in {@code prompts/list}.
     */
    record Prompt(String name, JsonObject definition, ApplicationMethod method) implements Feature {
    }
}
