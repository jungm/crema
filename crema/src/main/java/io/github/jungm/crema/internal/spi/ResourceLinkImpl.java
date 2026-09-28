package io.github.jungm.crema.internal.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.ResourceLink;

/**
 * Immutable {@link ResourceLink}. The title is the name unless set explicitly.
 */
record ResourceLinkImpl(String name, String title, String uri, Optional<String> description,
        Optional<String> mimeType, Optional<Annotations> annotations, OptionalLong size, Map<String, Object> metadata)
        implements ResourceLink {

    ResourceLinkImpl {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uri, "uri");
        title = title == null ? name : title;
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<ResourceLink.Builder> implements ResourceLink.Builder {

        private final String name;
        private final String uri;
        private String title;
        private String description;
        private String mimeType;
        private Annotations annotations;
        private OptionalLong size = OptionalLong.empty();

        Builder(String name, String uri) {
            this.name = Objects.requireNonNull(name, "name");
            this.uri = Objects.requireNonNull(uri, "uri");
        }

        @Override
        public ResourceLink.Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        @Override
        public ResourceLink.Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        @Override
        public ResourceLink.Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @Override
        public ResourceLink.Builder setAnnotations(Annotations annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public ResourceLink.Builder setSize(long size) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative: " + size);
            }
            this.size = OptionalLong.of(size);
            return this;
        }

        @Override
        public ResourceLink build() {
            return new ResourceLinkImpl(name, title, uri, Optional.ofNullable(description),
                    Optional.ofNullable(mimeType), Optional.ofNullable(annotations), size, metadata());
        }
    }
}
