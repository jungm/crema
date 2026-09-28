package io.github.jungm.crema.internal.security;

import java.util.Map;
import java.util.Optional;

import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.McpServerModel;
import jakarta.json.JsonObject;

/**
 * Decides who may use an MCP Server and its Features.
 */
public interface AccessPolicy {

    /**
     * Lets everyone use everything.
     */
    AccessPolicy PERMIT_ALL = new AccessPolicy() {
        @Override
        public Admission authenticate(McpServerModel server, Caller caller) {
            return new Admitted(caller);
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
        public boolean isPrivate(McpServerModel server, Caller caller) {
            return CallerPrincipal.safely(caller::principal) != null;
        }

        @Override
        public Optional<JsonObject> resourceMetadata(McpServerModel server) {
            return Optional.empty();
        }
    };

    /**
     * Checks a request before any MCP processing, after the {@code Origin} check, and determines the caller that
     * the rest of the request is processed for.
     *
     * @param caller the caller according to the Runtime
     * @return the caller to process the request for, or the response that rejects the request, such as a
     *         {@code 401} challenge
     */
    Admission authenticate(McpServerModel server, Caller caller);

    /**
     * Whether the caller may use a Feature or Completion Method. Features that aren't permitted are omitted from
     * lists.
     *
     * @param caller a caller {@link #authenticate admitted} by this policy
     */
    boolean permits(McpServerModel server, Feature feature, Caller caller);

    /**
     * The response to a request that invokes a Feature the caller isn't permitted to use.
     */
    Rejection forbidden(McpServerModel server, Caller caller);

    /**
     * Whether a result for this caller may depend on who the caller is, which makes its {@code cacheScope}
     * {@code private}.
     *
     * @param caller a caller {@link #authenticate admitted} by this policy
     */
    boolean isPrivate(McpServerModel server, Caller caller);

    /**
     * The Protected Resource Metadata (RFC 9728) of a protected MCP Server; empty for other MCP Servers. It
     * doesn't depend on the request.
     */
    Optional<JsonObject> resourceMetadata(McpServerModel server);

    /**
     * The outcome of {@link #authenticate}.
     */
    sealed interface Admission permits Admitted, Rejected {
    }

    /**
     * The request proceeds on behalf of {@code caller}.
     */
    record Admitted(Caller caller) implements Admission {
    }

    /**
     * The request is answered with {@code rejection}.
     */
    record Rejected(Rejection rejection) implements Admission {
    }

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
