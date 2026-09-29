package io.github.jungm.crema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The result of an {@link McpAuthenticator}: an authenticated caller, no credentials, or rejected credentials.
 */
public final class McpAuthentication {

    /**
     * The kind of result.
     */
    public enum Outcome {
        /** The request carries a caller's valid credentials. */
        AUTHENTICATED,
        /** The request carries no credentials the authenticator knows. */
        NONE,
        /** The request carries credentials that aren't valid. */
        REJECTED
    }

    private static final McpAuthentication NONE = new McpAuthentication(Outcome.NONE, null, Set.of(), Map.of());
    private static final McpAuthentication REJECTED = new McpAuthentication(Outcome.REJECTED, null, Set.of(),
            Map.of());

    private final Outcome outcome;
    private final String name;
    private final Set<String> roles;
    private final Map<String, Object> claims;

    private McpAuthentication(Outcome outcome, String name, Set<String> roles, Map<String, Object> claims) {
        this.outcome = outcome;
        this.name = name;
        this.roles = roles;
        this.claims = claims;
    }

    /**
     * The request carries no credentials: anonymous on an open MCP Server, {@code 401} on a protected one.
     */
    public static McpAuthentication none() {
        return NONE;
    }

    /**
     * The request carries invalid credentials: {@code 401}.
     */
    public static McpAuthentication rejected() {
        return REJECTED;
    }

    /**
     * An authenticated caller without claims.
     *
     * @param name the caller's name, which {@link McpCaller#getName()} returns
     * @param roles the caller's roles, which {@code @RolesAllowed} is checked against
     */
    public static McpAuthentication caller(String name, Set<String> roles) {
        return caller(name, roles, Map.of());
    }

    /**
     * An authenticated caller.
     *
     * @param name the caller's name, which {@link McpCaller#getName()} returns
     * @param roles the caller's roles, which {@code @RolesAllowed} is checked against
     * @param claims what else is known about the caller, which {@link McpCaller#claims()} returns
     * @throws IllegalArgumentException if {@code name} is empty
     */
    public static McpAuthentication caller(String name, Set<String> roles, Map<String, Object> claims) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The caller's name must not be empty");
        }
        return new McpAuthentication(Outcome.AUTHENTICATED, name, Set.copyOf(roles),
                Collections.unmodifiableMap(new LinkedHashMap<>(claims)));
    }

    public Outcome outcome() {
        return outcome;
    }

    /**
     * The caller's name; {@code null} unless {@link Outcome#AUTHENTICATED}.
     */
    public String name() {
        return name;
    }

    /**
     * The caller's roles; empty unless {@link Outcome#AUTHENTICATED}.
     */
    public Set<String> roles() {
        return roles;
    }

    /**
     * The caller's claims; empty unless {@link Outcome#AUTHENTICATED}. The map is unmodifiable.
     */
    public Map<String, Object> claims() {
        return claims;
    }

    @Override
    public String toString() {
        return outcome == Outcome.AUTHENTICATED ? "McpAuthentication[" + name + ", roles=" + roles + "]"
                : "McpAuthentication[" + outcome + "]";
    }
}
