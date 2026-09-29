package io.github.jungm.crema.internal.security;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import io.github.jungm.crema.internal.model.ApplicationMethod;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;
import jakarta.json.JsonObject;

/**
 * Decides who may use the MCP Servers of an application and their Features: role checks with
 * {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll} for every MCP Server, and the {@link Mechanism}
 * that authenticates the callers of an MCP Server, if it has one.
 * <ul>
 * <li>On a protected MCP Server every request needs a caller the mechanism authenticates; without credentials, the
 * answer is the mechanism's {@code 401} challenge.</li>
 * <li>On an open MCP Server with a mechanism, the mechanism's caller wins, and without credentials the caller is the
 * Runtime's.</li>
 * <li>On an open MCP Server without one, the caller and its roles are the Runtime's.</li>
 * </ul>
 */
public final class CremaAccessPolicy implements AutoCloseable {

    private final Map<Class<?>, McpServerAccess> servers = new LinkedHashMap<>();

    /**
     * @param mechanism how callers are authenticated, or {@code null} for the Runtime's caller
     * @param isProtected whether every request needs a caller that the mechanism authenticated
     */
    private record McpServerAccess(Mechanism mechanism, boolean isProtected,
            Map<ApplicationMethod, AccessRule> rules, boolean isPrivate) {
    }

    /**
     * The policy and the deployment problems found while building it; the policy is only usable if there are no
     * problems.
     */
    public record Result(CremaAccessPolicy policy, List<String> problems) {
    }

    private CremaAccessPolicy() {
    }

    /**
     * Builds the policy of an application's MCP Servers. The policy owns the mechanisms and closes them.
     *
     * @param mechanisms the mechanism of each MCP Server that has one, by {@code McpApplication} subclass; a
     *        protected MCP Server must have one
     */
    public static Result create(List<McpServerModel> servers, Map<Class<?>, Mechanism> mechanisms) {
        CremaAccessPolicy policy = new CremaAccessPolicy();
        Set<String> problems = new LinkedHashSet<>();
        for (McpServerModel server : servers) {
            Optional<AccessRule> application = AccessRule.ofApplication(server.application(), new ArrayList<>());
            Map<ApplicationMethod, AccessRule> rules = new IdentityHashMap<>();
            List<String> found = new ArrayList<>();
            boolean restricted = false;
            for (ApplicationMethod method : server.applicationMethods()) {
                AccessRule rule = AccessRule.of(method.method(), application, found);
                rules.put(method, rule);
                restricted |= rule.restricts();
            }
            problems.addAll(found);
            Mechanism mechanism = mechanisms.get(server.application());
            boolean isProtected = application.isPresent() && application.get().restricts();
            if (isProtected && mechanism == null) {
                problems.add("MCP Server '" + server.wireName() + "' is protected, but has no way to authenticate "
                        + "callers");
            }
            policy.servers.put(server.application(),
                    new McpServerAccess(mechanism, isProtected, rules, isProtected || restricted));
        }
        return new Result(policy, List.copyOf(problems));
    }

    /**
     * Checks a request before any MCP processing, after the {@code Origin} check, and determines the caller that
     * the rest of the request is processed for.
     *
     * @param caller the caller according to the Runtime
     * @param headers the values of a header field of the request, by case-insensitive name; empty when absent
     * @return the caller to process the request for: the mechanism's caller if it authenticated one, else
     *         {@code caller}
     * @throws Rejection a {@code 401} challenge
     */
    public Caller authenticate(McpServerModel server, Caller caller, Function<String, List<String>> headers) {
        McpServerAccess access = access(server);
        if (access.mechanism() == null) {
            return caller;
        }
        Optional<Caller> authenticated = access.mechanism().authenticate(server, headers);
        if (authenticated.isPresent()) {
            return authenticated.get();
        }
        if (access.isProtected()) {
            throw access.mechanism().unauthenticated();
        }
        return caller;
    }

    /**
     * Whether the caller may use a Feature Method or Completion Method. Features whose Feature Method isn't
     * permitted are omitted from lists.
     *
     * @param caller a caller {@link #authenticate admitted} for this MCP Server
     */
    public boolean permits(McpServerModel server, ApplicationMethod method, Caller caller) {
        McpServerAccess access = access(server);
        if (access.isProtected()
                && !(caller instanceof CremaCaller cremaCaller && cremaCaller.isFor(server.application()))) {
            return false;
        }
        return access.rules().get(method).permits(caller);
    }

    /**
     * The rejection of a request that invokes a Feature the caller may not use: {@code 403}, with a challenge if
     * the mechanism has one, such as OAuth's {@code insufficient_scope}.
     */
    public Rejection forbidden(McpServerModel server) {
        Mechanism mechanism = access(server).mechanism();
        return mechanism == null ? new Rejection(403, Map.of(), null) : mechanism.forbidden();
    }

    /**
     * Whether a result for this caller may depend on who the caller is, which makes its {@code cacheScope}
     * {@code private}: if the MCP Server is protected, if any of its Features is restricted to roles, or if the
     * caller is authenticated, by the Runtime or by Crema.
     *
     * @param caller a caller {@link #authenticate admitted} for this MCP Server
     */
    public boolean isPrivate(McpServerModel server, Caller caller) {
        return access(server).isPrivate() || caller instanceof CremaCaller
                || CallerPrincipal.safely(caller::principal) != null;
    }

    /**
     * The Protected Resource Metadata (RFC 9728) of an MCP Server with OAuth; empty for other MCP Servers.
     */
    public Optional<JsonObject> resourceMetadata(McpServerModel server) {
        Mechanism mechanism = access(server).mechanism();
        return mechanism == null ? Optional.empty() : mechanism.resourceMetadata();
    }

    /**
     * Closes the mechanisms, which stops the background retrieval of keys.
     */
    @Override
    public void close() {
        for (McpServerAccess access : servers.values()) {
            if (access.mechanism() != null) {
                access.mechanism().close();
            }
        }
    }

    private McpServerAccess access(McpServerModel server) {
        McpServerAccess access = servers.get(server.application());
        if (access == null) {
            throw new IllegalArgumentException("Unknown MCP Server '" + server.wireName() + "'");
        }
        return access;
    }
}
