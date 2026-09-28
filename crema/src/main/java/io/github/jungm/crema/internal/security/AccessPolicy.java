package io.github.jungm.crema.internal.security;

import java.util.Map;
import java.util.Optional;

import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.McpServerModel;

/**
 * Decides who may use an MCP Server and its Features.
 */
public interface AccessPolicy {

    /**
     * Lets everyone use everything.
     */
    AccessPolicy PERMIT_ALL = new AccessPolicy() {
        @Override
        public Optional<Rejection> authenticate(McpServerModel server, Caller caller) {
            return Optional.empty();
        }

        @Override
        public boolean permits(McpServerModel server, Feature feature, Caller caller) {
            return true;
        }

        @Override
        public Rejection forbidden(McpServerModel server, Caller caller) {
            return new Rejection(403, Map.of());
        }

        @Override
        public boolean isPrivate(McpServerModel server) {
            return false;
        }
    };

    /**
     * Checks a request before any MCP processing, after the {@code Origin} check.
     *
     * @return the response that rejects the request, such as a {@code 401} challenge, or empty to proceed
     */
    Optional<Rejection> authenticate(McpServerModel server, Caller caller);

    /**
     * Whether the caller may use a Feature or Completion Method. Features that aren't permitted are omitted from
     * lists.
     */
    boolean permits(McpServerModel server, Feature feature, Caller caller);

    /**
     * The response to a request that invokes a Feature the caller isn't permitted to use.
     */
    Rejection forbidden(McpServerModel server, Caller caller);

    /**
     * Whether results of the MCP Server depend on the caller, which makes their {@code cacheScope}
     * {@code private}.
     */
    boolean isPrivate(McpServerModel server);

    /**
     * An HTTP response without a JSON-RPC body.
     */
    record Rejection(int status, Map<String, String> headers) {

        public Rejection {
            headers = Map.copyOf(headers);
        }
    }

    /**
     * Carries a {@link Rejection} out of request processing.
     */
    class RejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Rejection rejection;

        public RejectedException(Rejection rejection) {
            super("HTTP " + rejection.status(), null, false, false);
            this.rejection = rejection;
        }

        public Rejection rejection() {
            return rejection;
        }
    }
}
