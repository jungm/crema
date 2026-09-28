package io.github.jungm.crema.internal.invoke;

import java.util.Optional;

import org.mcpjava.server.Cancellation;

/**
 * The {@link Cancellation} of every request: Crema doesn't signal cancellation.
 */
public enum NeverCancelled implements Cancellation, Cancellation.Result {

    INSTANCE;

    @Override
    public Result check() {
        return this;
    }

    @Override
    public boolean isRequested() {
        return false;
    }

    @Override
    public Optional<String> reason() {
        return Optional.empty();
    }
}
