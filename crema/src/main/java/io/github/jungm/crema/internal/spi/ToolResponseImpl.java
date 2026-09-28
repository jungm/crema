package io.github.jungm.crema.internal.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.tools.ToolResponse;

/**
 * Immutable {@link ToolResponse}. The structured content is held by reference and encoded to JSON when
 * the response is written.
 */
record ToolResponseImpl(List<ContentBlock> content, Optional<Object> structuredContent, boolean isError,
        Map<String, Object> metadata) implements ToolResponse {

    ToolResponseImpl {
        content = Meta.copy(content);
        Objects.requireNonNull(structuredContent, "structuredContent");
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<ToolResponse.Builder> implements ToolResponse.Builder {

        private final List<ContentBlock> content = new ArrayList<>();
        private Object structuredContent;
        private boolean error;

        @Override
        public ToolResponse.Builder addContent(ContentBlock content) {
            this.content.add(Objects.requireNonNull(content, "content"));
            return this;
        }

        @Override
        public ToolResponse.Builder addTextContent(String textContent) {
            content.add(new TextContentImpl(textContent, Optional.empty(), Map.of()));
            return this;
        }

        @Override
        public ToolResponse.Builder setStructuredContent(Object structuredContent) {
            this.structuredContent = structuredContent;
            return this;
        }

        @Override
        public ToolResponse.Builder setError(boolean isError) {
            this.error = isError;
            return this;
        }

        @Override
        public ToolResponse build() {
            return new ToolResponseImpl(content, Optional.ofNullable(structuredContent), error, metadata());
        }
    }
}
