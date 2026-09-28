package io.github.jungm.crema.internal.security;

import java.security.Principal;
import java.util.Map;

import io.github.jungm.crema.McpCaller;

/**
 * Who sends the current request: the caller the Runtime authenticated, or the caller of a validated bearer token.
 * Some Runtimes throw from {@link #principal()} and {@link #isUserInRole(String)} when the request carries no
 * credentials they know, which means that there is no principal.
 */
public interface Caller {

    Caller ANONYMOUS = new Caller() {
        @Override
        public Principal principal() {
            return null;
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }
    };

    /**
     * The authenticated principal, or {@code null}.
     */
    Principal principal();

    boolean isUserInRole(String role);

    /**
     * The caller as Feature Methods see it, or {@code null} for an anonymous caller. The claims are empty unless
     * the caller was authenticated by a bearer token.
     */
    default McpCaller mcpCaller() {
        Principal principal = CallerPrincipal.safely(this::principal);
        return principal == null ? null : new CallerPrincipal(principal.getName(), Map.of());
    }
}
