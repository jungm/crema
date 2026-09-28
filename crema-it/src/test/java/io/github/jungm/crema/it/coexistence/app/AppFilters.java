package io.github.jungm.crema.it.coexistence.app;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** The application's own request filters, which must not affect MCP traffic. */
public final class AppFilters {

    /** The header every request to the application's resources must carry, with {@link #KEY}. */
    public static final String KEY_HEADER = "X-App-Key";
    public static final String KEY = "app-secret";

    /** A request with this header is blocked before resource matching. */
    public static final String BLOCK_HEADER = "X-App-Block";
    public static final int BLOCKED = 451;

    private AppFilters() {
    }

    /** Rejects every request without the application's key with {@code 401}, after resource matching. */
    @Provider
    public static class RequireKey implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            if (!KEY.equals(request.getHeaderString(KEY_HEADER))) {
                request.abortWith(Response.status(401).type(MediaType.TEXT_PLAIN)
                        .entity("key required by the application").build());
            }
        }
    }

    /** Blocks requests with {@link #BLOCK_HEADER} before resource matching. */
    @Provider
    @PreMatching
    public static class Block implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getHeaderString(BLOCK_HEADER) != null) {
                request.abortWith(Response.status(BLOCKED).type(MediaType.TEXT_PLAIN)
                        .entity("blocked by the application").build());
            }
        }
    }
}
