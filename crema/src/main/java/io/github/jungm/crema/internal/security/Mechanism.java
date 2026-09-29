package io.github.jungm.crema.internal.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;
import jakarta.json.JsonObject;

/**
 * How Crema authenticates the callers of one MCP Server, and how it answers those it doesn't admit. Why credentials
 * were rejected is logged, never returned.
 */
public interface Mechanism extends AutoCloseable {

    /**
     * Determines the caller of a request from its header fields.
     *
     * @param headers the values of a header field of the request, by case-insensitive name; empty when absent
     * @return the caller, authenticated for {@code server}; empty if the request carries no credentials this
     *         mechanism knows
     * @throws Rejection a {@code 401} challenge if the credentials are invalid
     */
    Optional<Caller> authenticate(McpServerModel server, Function<String, List<String>> headers);

    /**
     * The {@code 401} challenge to a request without credentials on a protected MCP Server.
     */
    Rejection unauthenticated();

    /**
     * The rejection of a request that invokes a Feature the caller may not use: a plain {@code 403} unless the
     * mechanism has a way to ask for more.
     */
    default Rejection forbidden() {
        return new Rejection(403, Map.of(), null);
    }

    /**
     * The Protected Resource Metadata (RFC 9728) of the MCP Server, if the mechanism has one.
     */
    default Optional<JsonObject> resourceMetadata() {
        return Optional.empty();
    }

    /**
     * Releases what the mechanism holds, such as background key retrieval.
     */
    @Override
    default void close() {
    }
}
