package io.github.jungm.crema.internal.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.resources.ResourceContents;
import io.github.jungm.crema.internal.json.MetaKeys;

/**
 * Immutable {@link EmbeddedResource}.
 */
record EmbeddedResourceImpl(ResourceContents resource, Optional<Annotations> annotations, Map<String, Object> metadata)
        implements EmbeddedResource {

    EmbeddedResourceImpl {
        Objects.requireNonNull(resource, "resource");
        metadata = Meta.copy(metadata);
    }

    /**
     * Builds an embedded resource around text or blob contents; exactly one of {@code text} and
     * {@code blob} is non-null.
     */
    static final class Builder extends MetaBuilder<EmbeddedResource.Builder> implements EmbeddedResource.Builder {

        private final String uri;
        private final String text;
        private final byte[] blob;
        private final Map<String, Object> resourceMeta = new LinkedHashMap<>();
        private String mimeType;
        private Annotations annotations;

        private Builder(String uri, String text, byte[] blob) {
            this.uri = Objects.requireNonNull(uri, "uri");
            this.text = text;
            this.blob = blob;
        }

        static Builder text(String text, String uri) {
            return new Builder(uri, Objects.requireNonNull(text, "text"), null);
        }

        static Builder blob(byte[] data, String uri) {
            return new Builder(uri, null, Meta.copy(data));
        }

        @Override
        public EmbeddedResource.Builder setAnnotations(Annotations annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public EmbeddedResource.Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @Override
        public EmbeddedResource.Builder putResourceMeta(String key, Object value) {
            resourceMeta.put(MetaKeys.requireValid(key), value);
            return this;
        }

        @Override
        public EmbeddedResource build() {
            ResourceContents contents = text != null
                    ? new TextResourceContentsImpl(uri, text, Optional.ofNullable(mimeType), resourceMeta)
                    : new BlobResourceContentsImpl(uri, blob, Optional.ofNullable(mimeType), resourceMeta);
            return new EmbeddedResourceImpl(contents, Optional.ofNullable(annotations), metadata());
        }
    }
}
