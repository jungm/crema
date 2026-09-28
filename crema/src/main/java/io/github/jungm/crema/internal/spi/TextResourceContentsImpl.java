package io.github.jungm.crema.internal.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.resources.TextResourceContents;

/**
 * Immutable {@link TextResourceContents}.
 */
record TextResourceContentsImpl(String uri, String text, Optional<String> mimeType, Map<String, Object> metadata)
        implements TextResourceContents {

    TextResourceContentsImpl {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(text, "text");
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<TextResourceContents.Builder>
            implements TextResourceContents.Builder {

        private final String uri;
        private final String text;
        private String mimeType;

        Builder(String uri, String text) {
            this.uri = Objects.requireNonNull(uri, "uri");
            this.text = Objects.requireNonNull(text, "text");
        }

        @Override
        public TextResourceContents.Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @Override
        public TextResourceContents build() {
            return new TextResourceContentsImpl(uri, text, Optional.ofNullable(mimeType), metadata());
        }
    }
}
