package io.github.jungm.crema.internal.model;

/**
 * Obtains the instance a Feature Method is invoked on, once per call.
 */
@FunctionalInterface
public interface InstanceSource {

    Handle acquire();

    /**
     * An instance for one call; {@link #close()} releases it (and destroys it if it is dependent).
     */
    interface Handle extends AutoCloseable {

        Object get();

        @Override
        void close();
    }

    /**
     * A source that always yields the same instance.
     */
    static InstanceSource of(Object instance) {
        return () -> new Handle() {
            @Override
            public Object get() {
                return instance;
            }

            @Override
            public void close() {
            }
        };
    }
}
