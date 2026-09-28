package io.github.jungm.crema.internal.security;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.crema.McpCaller;

/**
 * The {@link McpCaller} that Feature Methods receive.
 */
final class CallerPrincipal implements McpCaller {

    private static final Logger LOG = Logger.getLogger(CallerPrincipal.class.getName());

    private final String name;
    private final Map<String, Object> claims;

    CallerPrincipal(String name, Map<String, Object> claims) {
        this.name = Objects.requireNonNull(name);
        this.claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Map<String, Object> claims() {
        return claims;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CallerPrincipal that && name.equals(that.name) && claims.equals(that.claims);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Asks the Runtime for the principal. Some Runtimes throw when the request carries no credentials they know,
     * which means there is no principal.
     */
    static Principal safely(Supplier<Principal> principal) {
        try {
            return principal.get();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "The Runtime couldn't tell the caller; treating it as anonymous", e);
            return null;
        }
    }
}
