package io.github.jungm.crema.internal.invoke;

import java.util.Map;
import java.util.Optional;

import org.mcpjava.server.completion.CompletionContext;

/**
 * The values a {@code completion/complete} request carries for other Arguments.
 */
public record CompletionContextImpl(Map<String, String> arguments) implements CompletionContext {

    public CompletionContextImpl {
        arguments = Map.copyOf(arguments);
    }

    @Override
    public Optional<String> getArgument(String name) {
        return Optional.ofNullable(arguments.get(name));
    }
}
