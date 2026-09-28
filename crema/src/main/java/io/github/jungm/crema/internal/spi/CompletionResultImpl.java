package io.github.jungm.crema.internal.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.mcpjava.server.completion.CompletionResult;

/**
 * Immutable {@link CompletionResult}. It may hold more than 100 values; the wire encoding caps them.
 */
record CompletionResultImpl(List<String> values, OptionalInt total, Optional<Boolean> hasMore,
        Map<String, Object> metadata) implements CompletionResult {

    CompletionResultImpl {
        values = Meta.copy(values);
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(hasMore, "hasMore");
        metadata = Meta.copy(metadata);
    }

    static final class Builder extends MetaBuilder<CompletionResult.Builder> implements CompletionResult.Builder {

        private final List<String> values = new ArrayList<>();
        private OptionalInt total = OptionalInt.empty();
        private Boolean hasMore;

        @Override
        public CompletionResult.Builder addValue(String value) {
            values.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        @Override
        public CompletionResult.Builder addValues(Collection<String> values) {
            values.forEach(this::addValue);
            return this;
        }

        @Override
        public CompletionResult.Builder setTotal(int total) {
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative: " + total);
            }
            this.total = OptionalInt.of(total);
            return this;
        }

        @Override
        public CompletionResult.Builder setHasMore(Boolean hasMore) {
            this.hasMore = hasMore;
            return this;
        }

        @Override
        public CompletionResult build() {
            return new CompletionResultImpl(values, total, Optional.ofNullable(hasMore), metadata());
        }
    }
}
