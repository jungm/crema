package io.github.jungm.crema.internal.spi;

import java.util.Objects;

import org.mcpjava.server.Role;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.prompts.PromptMessage;

/**
 * Immutable {@link PromptMessage}.
 */
record PromptMessageImpl(Role role, ContentBlock content) implements PromptMessage {

    PromptMessageImpl {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
