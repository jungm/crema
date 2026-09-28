package io.github.jungm.crema.internal.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.TextContent;

/**
 * Immutable {@link TextContent}.
 */
record TextContentImpl(String text, Optional<Annotations> annotations, Map<String, Object> metadata)
        implements TextContent {

    TextContentImpl {
        Objects.requireNonNull(text, "text");
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<TextContent.Builder> implements TextContent.Builder {

        private final String text;
        private Annotations annotations;

        Builder(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        @Override
        public TextContent.Builder setAnnotations(Annotations annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public TextContent build() {
            return new TextContentImpl(text, Optional.ofNullable(annotations), metadata());
        }
    }
}
