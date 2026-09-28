package io.github.jungm.crema.internal.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.mcpjava.server.resources.ResourceContents;
import org.mcpjava.server.resources.ResourceResponse;

/**
 * Immutable {@link ResourceResponse}.
 */
record ResourceResponseImpl(List<ResourceContents> contents, Map<String, Object> metadata) implements ResourceResponse {

    ResourceResponseImpl {
        contents = Meta.copy(contents);
        metadata = Meta.copy(metadata);
    }

    @Override
    public List<ResourceContents> getContents() {
        return contents;
    }

    static final class Builder extends MetaBuilder<ResourceResponse.Builder> implements ResourceResponse.Builder {

        private final List<ResourceContents> contents = new ArrayList<>();

        @Override
        public ResourceResponse.Builder addContents(ResourceContents contents) {
            this.contents.add(Objects.requireNonNull(contents, "contents"));
            return this;
        }

        @Override
        public ResourceResponse build() {
            return new ResourceResponseImpl(contents, metadata());
        }
    }
}
