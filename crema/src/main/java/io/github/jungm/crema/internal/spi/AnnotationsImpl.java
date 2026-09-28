package io.github.jungm.crema.internal.spi;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.mcpjava.server.Role;
import org.mcpjava.server.content.Annotations;

/**
 * Immutable {@link Annotations}. The audience iterates in {@link Role} declaration order.
 */
record AnnotationsImpl(Optional<Set<Role>> audience, OptionalDouble priority, Optional<Instant> lastModified)
        implements Annotations {

    AnnotationsImpl {
        audience = audience.map(AnnotationsImpl::roles);
    }

    private static Set<Role> roles(Collection<Role> roles) {
        EnumSet<Role> copy = EnumSet.noneOf(Role.class);
        roles.forEach(role -> copy.add(Objects.requireNonNull(role, "role")));
        return Collections.unmodifiableSet(copy);
    }

    static final class Builder implements Annotations.Builder {

        private Set<Role> audience;
        private OptionalDouble priority = OptionalDouble.empty();
        private Instant lastModified;

        @Override
        public Annotations.Builder setAudience(Role... roles) {
            audience = roles(Arrays.asList(roles));
            return this;
        }

        @Override
        public Annotations.Builder setAudience(Set<Role> roles) {
            audience = roles(roles);
            return this;
        }

        @Override
        public Annotations.Builder setPriority(double priority) {
            if (!(priority >= 0.0 && priority <= 1.0)) {
                throw new IllegalArgumentException("priority must be between 0.0 and 1.0: " + priority);
            }
            this.priority = OptionalDouble.of(priority);
            return this;
        }

        @Override
        public Annotations.Builder setLastModified(Instant lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        @Override
        public Annotations build() {
            return new AnnotationsImpl(Optional.ofNullable(audience), priority, Optional.ofNullable(lastModified));
        }
    }
}
