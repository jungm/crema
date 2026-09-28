package io.github.jungm.crema.internal.spi;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.resources.BlobResourceContents;

/**
 * Immutable {@link BlobResourceContents}. The blob is copied on the way in and out.
 */
record BlobResourceContentsImpl(String uri, byte[] blob, Optional<String> mimeType, Map<String, Object> metadata)
        implements BlobResourceContents {

    BlobResourceContentsImpl {
        Objects.requireNonNull(uri, "uri");
        blob = Meta.copy(blob);
        metadata = Meta.copy(metadata);
    }

    @Override
    public byte[] blob() {
        return blob.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlobResourceContentsImpl other && uri.equals(other.uri) && Arrays.equals(blob, other.blob)
                && mimeType.equals(other.mimeType) && metadata.equals(other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, Arrays.hashCode(blob), mimeType, metadata);
    }

    @Override
    public String toString() {
        return "BlobResourceContents[uri=" + uri + ", " + blob.length + " bytes, mimeType=" + mimeType + ", metadata="
                + metadata + "]";
    }

    static final class Builder extends MetaBuilder<BlobResourceContents.Builder>
            implements BlobResourceContents.Builder {

        private final String uri;
        private final byte[] blob;
        private String mimeType;

        Builder(String uri, byte[] blob) {
            this.uri = Objects.requireNonNull(uri, "uri");
            this.blob = Meta.copy(blob);
        }

        @Override
        public BlobResourceContents.Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @Override
        public BlobResourceContents build() {
            return new BlobResourceContentsImpl(uri, blob, Optional.ofNullable(mimeType), metadata());
        }
    }
}
