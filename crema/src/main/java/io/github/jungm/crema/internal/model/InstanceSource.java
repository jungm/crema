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
}
