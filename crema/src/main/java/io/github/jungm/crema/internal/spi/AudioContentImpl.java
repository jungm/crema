package io.github.jungm.crema.internal.spi;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.AudioContent;

/**
 * Immutable {@link AudioContent}. The audio data is copied on the way in and out.
 */
record AudioContentImpl(byte[] data, String mimeType, Optional<Annotations> annotations, Map<String, Object> metadata)
        implements AudioContent {

    AudioContentImpl {
        data = Meta.copy(data);
        Objects.requireNonNull(mimeType, "mimeType");
        metadata = Meta.copy(metadata);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AudioContentImpl other && Arrays.equals(data, other.data) && mimeType.equals(other.mimeType)
                && annotations.equals(other.annotations) && metadata.equals(other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(data), mimeType, annotations, metadata);
    }

    @Override
    public String toString() {
        return "AudioContent[" + data.length + " bytes, mimeType=" + mimeType + ", annotations=" + annotations
                + ", metadata=" + metadata + "]";
    }

    static final class Builder extends MetaBuilder<AudioContent.Builder> implements AudioContent.Builder {

        private final byte[] data;
        private final String mimeType;
        private Annotations annotations;

        Builder(byte[] data, String mimeType) {
            this.data = Meta.copy(data);
            this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        }

        @Override
        public AudioContent.Builder setAnnotations(Annotations annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public AudioContent build() {
            return new AudioContentImpl(data, mimeType, Optional.ofNullable(annotations), metadata());
        }
    }
}
