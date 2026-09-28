package io.github.jungm.crema.internal.security;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import io.github.jungm.crema.McpCaller;

/**
 * A caller authenticated by a validated bearer token for one protected MCP Server. Its name and roles come from
 * the token's claims; the Runtime's view of the caller plays no part.
 */
final class TokenCaller implements Caller {

    private final Class<?> application;
    private final CallerPrincipal principal;
    private final Set<String> roles;

    /**
     * @param application the {@code McpApplication} subclass of the MCP Server the token was validated for
     */
    TokenCaller(Class<?> application, CallerPrincipal principal, Set<String> roles) {
        this.application = application;
        this.principal = principal;
        this.roles = Set.copyOf(roles);
    }

    /**
     * Whether the token was validated for the MCP Server of an {@code McpApplication} subclass.
     */
    boolean isFor(Class<?> application) {
        return this.application == application;
    }

    @Override
    public Principal principal() {
        return principal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return roles.contains(role);
    }

    @Override
    public McpCaller mcpCaller() {
        return principal;
    }

    /**
     * The value at a dotted path of already validated claims, such as {@code realm_access.roles}. A claim whose
     * name is the whole path, dots included, takes precedence.
     */
    static Object claim(Map<String, Object> claims, String path) {
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object value = claims;
        for (String segment : path.split("\\.", -1)) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(segment);
        }
        return value;
    }

    /**
     * The roles in a claim: the strings of an array, or a single string.
     */
    static Set<String> roles(Object claim) {
        Collection<String> roles = new ArrayList<>();
        if (claim instanceof String role) {
            roles.add(role);
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String role) {
                    roles.add(role);
                }
            }
        }
        return Set.copyOf(roles);
    }
}
