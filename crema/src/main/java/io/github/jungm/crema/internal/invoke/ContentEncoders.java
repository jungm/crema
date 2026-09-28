package io.github.jungm.crema.internal.invoke;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.mcpjava.server.ContentEncoder;
import org.mcpjava.server.content.ContentBlock;

import io.github.jungm.crema.internal.model.InstanceSource;

/**
 * The application's {@link ContentEncoder}s. The encoder whose type is the most specific supertype of a value's
 * class encodes it.
 */
public final class ContentEncoders {

    /**
     * An encoder and, if known without instantiating it, the type it encodes.
     *
     * @param type the encoded type, or {@code null} to ask the encoder's {@link ContentEncoder#getType()}
     */
    public record Candidate(Class<?> type, InstanceSource instances) {
    }

    private record Resolved(Class<?> type, InstanceSource instances) {
    }

    private final Supplier<List<Candidate>> candidates;
    private volatile List<Resolved> resolved;

    public ContentEncoders(Supplier<List<Candidate>> candidates) {
        this.candidates = candidates;
    }

    /**
     * Encodes a value with the most specific encoder, if one applies.
     */
    public Optional<ContentBlock> encode(Object value) {
        Resolved best = null;
        for (Resolved encoder : resolved()) {
            if (encoder.type().isInstance(value) && (best == null
                    || best.type() != encoder.type() && best.type().isAssignableFrom(encoder.type()))) {
                best = encoder;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        try (InstanceSource.Handle handle = best.instances().acquire()) {
            @SuppressWarnings("unchecked")
            ContentEncoder<Object> encoder = (ContentEncoder<Object>) handle.get();
            return Optional.ofNullable(encoder.encode(value));
        }
    }

    private List<Resolved> resolved() {
        List<Resolved> result = resolved;
        if (result == null) {
            result = candidates.get().stream().map(ContentEncoders::resolve)
                    .sorted(Comparator.comparing(r -> r.type().getName())).toList();
            resolved = result;
        }
        return result;
    }

    private static Resolved resolve(Candidate candidate) {
        if (candidate.type() != null) {
            return new Resolved(candidate.type(), candidate.instances());
        }
        try (InstanceSource.Handle handle = candidate.instances().acquire()) {
            return new Resolved(((ContentEncoder<?>) handle.get()).getType(), candidate.instances());
        }
    }
}
