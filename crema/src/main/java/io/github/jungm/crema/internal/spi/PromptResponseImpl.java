package io.github.jungm.crema.internal.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mcpjava.server.Role;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.prompts.PromptResponse;

/**
 * Immutable {@link PromptResponse}.
 */
record PromptResponseImpl(Optional<String> description, List<PromptMessage> messages, Map<String, Object> metadata)
        implements PromptResponse {

    PromptResponseImpl {
        messages = Meta.copy(messages);
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<PromptResponse.Builder> implements PromptResponse.Builder {

        private final List<PromptMessage> messages = new ArrayList<>();
        private String description;

        @Override
        public PromptResponse.Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        @Override
        public PromptResponse.Builder addMessage(Role role, ContentBlock content) {
            messages.add(new PromptMessageImpl(role, content));
            return this;
        }

        @Override
        public PromptResponse build() {
            return new PromptResponseImpl(Optional.ofNullable(description), messages, metadata());
        }
    }
}
