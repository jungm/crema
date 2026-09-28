package io.github.jungm.crema.internal.security;

import java.security.Principal;
import java.util.Optional;

import jakarta.ws.rs.core.SecurityContext;

/**
 * The JAX-RS {@link SecurityContext} of an MCP request authenticated by a bearer token: the principal and roles
 * come from the token, {@link #isSecure()} from the request, and the authentication scheme is {@code Bearer}. It
 * replaces the Runtime's security context for MCP processing only; the Runtime itself doesn't see the caller.
 */
public final class TokenSecurityContext implements SecurityContext {

    public static final String BEARER = "Bearer";

    private final TokenCaller caller;
    private final boolean secure;

    private TokenSecurityContext(TokenCaller caller, boolean secure) {
        this.caller = caller;
        this.secure = secure;
    }

    /**
     * The security context to process a request with once an {@link AccessPolicy} has admitted it.
     *
     * @param original the Runtime's security context
     * @param admitted the caller the request was admitted for
     * @return the replacement, or empty if the Runtime's security context stays
     */
    public static Optional<SecurityContext> replacing(SecurityContext original, Caller admitted) {
        if (admitted instanceof TokenCaller tokenCaller) {
            return Optional.of(new TokenSecurityContext(tokenCaller, original != null && original.isSecure()));
        }
        return Optional.empty();
    }

    /**
     * The token caller of a security context that {@link #replacing} created.
     */
    public static Optional<Caller> caller(SecurityContext context) {
        return context instanceof TokenSecurityContext token ? Optional.of(token.caller) : Optional.empty();
    }

    @Override
    public Principal getUserPrincipal() {
        return caller.principal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return caller.isUserInRole(role);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String getAuthenticationScheme() {
        return BEARER;
    }
}
