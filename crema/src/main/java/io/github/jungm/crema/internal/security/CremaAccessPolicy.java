package io.github.jungm.crema.internal.security;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWTClaimsSet;

import io.github.jungm.crema.McpAuthentication;
import io.github.jungm.crema.McpAuthenticator;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.model.ApplicationMethod;
import io.github.jungm.crema.internal.model.InstanceSource;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Decides who may use the MCP Servers of an application and their Features: role checks with
 * {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll} for every MCP Server, bearer tokens for protected
 * MCP Servers with OAuth, and {@link McpAuthenticator}s.
 * <ul>
 * <li>On an MCP Server with an authenticator, the caller and its roles are the ones the authenticator returns.
 * Rejected credentials are answered with a {@code 401} with {@code error="invalid_token"}. Without credentials, a
 * protected MCP Server answers with a {@code 401}, and an open one uses the Runtime's caller. No challenge points to
 * Protected Resource Metadata.</li>
 * <li>On a protected MCP Server with OAuth, every request needs a bearer token that {@link TokenValidator} accepts,
 * and the caller and its roles come from the token. Without one the response is a {@code 401} challenge that points
 * to the Protected Resource Metadata; with an invalid one it is a {@code 401} with {@code error="invalid_token"}.
 * Both challenges carry the configured scopes, if any.</li>
 * <li>On any other MCP Server the caller and its roles are the Runtime's.</li>
 * </ul>
 * Why credentials were rejected is logged, never returned.
 */
public final class CremaAccessPolicy implements Closeable {

    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String BEARER = "Bearer";
    private static final String AUTHORIZATION = "Authorization";

    private static final Logger LOG = Logger.getLogger(CremaAccessPolicy.class.getName());

    private final Map<Class<?>, McpServerAccess> servers = new LinkedHashMap<>();

    /**
     * @param protection the OAuth protection, or {@code null}
     * @param authenticator the MCP Server's authenticator, or {@code null}
     * @param isProtected whether every request needs a caller that Crema authenticated for this MCP Server
     * @param failures lets through one warning per minute about failures to authenticate that aren't the
     *        credentials' fault, such as unavailable keys or a failing authenticator
     */
    private record McpServerAccess(String server, Protection protection, TokenValidator validator,
            Authenticator authenticator, boolean isProtected, Map<ApplicationMethod, AccessRule> rules,
            boolean isPrivate, LogText.Throttle failures) {
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
     * Builds the policy of an application's MCP Servers.
     *
     * @param protections the OAuth protection of each protected MCP Server that uses OAuth, by
     *        {@code McpApplication} subclass
     * @param authenticators the authenticator of each MCP Server that has one, by {@code McpApplication} subclass;
     *        a protected MCP Server needs either an OAuth protection or an authenticator
     */
    public static Result create(List<McpServerModel> servers, Map<Class<?>, Protection> protections,
            Map<Class<?>, Authenticator> authenticators) {
        return create(servers, protections, authenticators, TokenValidator.Tuning.DEFAULT);
    }

    static Result create(List<McpServerModel> servers, Map<Class<?>, Protection> protections,
            Map<Class<?>, Authenticator> authenticators, TokenValidator.Tuning tuning) {
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
            Protection protection = protections.get(server.application());
            Authenticator authenticator = authenticators.get(server.application());
            boolean isProtected = protection != null
                    || authenticator != null && application.isPresent() && application.get().restricts();
            TokenValidator validator = protection == null ? null : new TokenValidator(protection, tuning);
            policy.servers.put(server.application(),
                    new McpServerAccess(server.wireName(), protection, validator, authenticator, isProtected, rules,
                            isProtected || restricted, new LogText.Throttle(1, TimeUnit.MINUTES)));
        }
        return new Result(policy, List.copyOf(problems));
    }

    /**
     * Checks a request before any MCP processing, after the {@code Origin} check, and determines the caller that
     * the rest of the request is processed for.
     *
     * @param caller the caller according to the Runtime
     * @param headers the values of a header field of the request, by case-insensitive name; empty when absent
     * @return the caller to process the request for: the authenticator's caller on an MCP Server with an
     *         authenticator, the token's caller on a protected one with OAuth, else {@code caller}
     * @throws Rejection a {@code 401} challenge
     */
    public Caller authenticate(McpServerModel server, Caller caller, Function<String, List<String>> headers) {
        McpServerAccess access = access(server);
        if (access.authenticator() != null) {
            Optional<Caller> authenticated = authenticateWith(server, access, headers);
            if (authenticated.isPresent()) {
                return authenticated.get();
            }
            if (access.isProtected()) {
                throw challenge(null, null);
            }
            return caller;
        }
        if (access.protection() == null) {
            return caller;
        }
        Protection protection = access.protection();
        List<String> authorization = headers.apply(AUTHORIZATION);
        if (authorization.isEmpty()) {
            throw challenge(protection, null);
        }
        if (authorization.size() > 1) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + access.protection().server()
                    + "' with more than one Authorization header");
            throw challenge(protection, "invalid_token");
        }
        String value = authorization.get(0).strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase(BEARER)) {
            throw challenge(protection, null);
        }
        String token = space < 0 ? "" : value.substring(space + 1).stripLeading();
        return validate(server, access, token).orElseThrow(() -> challenge(protection, "invalid_token"));
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
     * The rejection of a request that invokes a Feature the caller may not use: {@code 403}, with an
     * {@code insufficient_scope} challenge on a protected MCP Server with OAuth.
     */
    public Rejection forbidden(McpServerModel server) {
        McpServerAccess access = access(server);
        if (access.protection() == null) {
            return new Rejection(403, Map.of(), null);
        }
        return new Rejection(403, Map.of(WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", resource_metadata=\""
                        + access.protection().resourceMetadataUrl() + "\""), null);
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
     * The Protected Resource Metadata (RFC 9728) of a protected MCP Server with OAuth, with {@code scopes_supported} if
     * scopes are configured; empty for other MCP Servers. It doesn't depend on the request.
     */
    public Optional<JsonObject> resourceMetadata(McpServerModel server) {
        Protection protection = access(server).protection();
        if (protection == null) {
            return Optional.empty();
        }
        JsonObjectBuilder metadata = Json.object()
                .add("resource", protection.resource())
                .add("authorization_servers", Json.FACTORY.createArrayBuilder().add(protection.issuer()))
                .add("bearer_methods_supported", Json.FACTORY.createArrayBuilder().add("header"));
        if (!protection.scopes().isEmpty()) {
            metadata.add("scopes_supported", Json.FACTORY.createArrayBuilder(protection.scopes()));
        }
        return Optional.of(metadata.build());
    }

    /**
     * Stops the background retrieval of keys.
     */
    @Override
    public void close() {
        for (McpServerAccess access : servers.values()) {
            if (access.validator() != null) {
                try {
                    access.validator().close();
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.FINE, "Couldn't close the key source of MCP Server '"
                            + access.protection().server() + "'", e);
                }
            }
        }
    }

    private Optional<Caller> validate(McpServerModel server, McpServerAccess access, String token) {
        Protection protection = access.protection();
        if (token.isEmpty()) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server() + "': empty bearer token");
            return Optional.empty();
        }
        if (!Credentials.B64TOKEN.matcher(token).matches()) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server() + "': the bearer token has "
                    + "characters outside the RFC 6750 b64token syntax, such as whitespace");
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = access.validator().validate(token);
        } catch (RateLimitReachedException e) {
            LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server() + "': no key of issuer "
                    + protection.issuer() + " matches it, and the keys were retrieved too recently to retrieve "
                    + "them again");
            return Optional.empty();
        } catch (KeySourceException e) {
            tokenFailure(access, "the keys of issuer " + protection.issuer() + " aren't available: "
                    + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (ParseException | BadJOSEException | JOSEException e) {
            LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server() + "' (resource "
                    + protection.resource() + "): " + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (RuntimeException e) {
            tokenFailure(access, "validating it failed: " + e.getClass().getName() + ": " + LogText.clean(e.getMessage()));
            return Optional.empty();
        }
        Map<String, Object> values = claims.getClaims();
        Object name = CremaCaller.claim(values, protection.principalClaim());
        if (!(name instanceof String principal) || principal.isEmpty()) {
            LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server()
                    + "': its principal claim " + protection.principalClaim() + " isn't a non-empty string");
            return Optional.empty();
        }
        return Optional.of(new CremaCaller(server.application(), new CallerPrincipal(principal, values),
                CremaCaller.roles(CremaCaller.claim(values, protection.rolesClaim()))));
    }

    /**
     * Asks the MCP Server's authenticator who sends a request.
     *
     * @return the authenticated caller; empty if the request carries no credentials
     * @throws Rejection an {@code invalid_token} challenge if the credentials are rejected
     */
    private Optional<Caller> authenticateWith(McpServerModel server, McpServerAccess access,
            Function<String, List<String>> headers) {
        Authenticator authenticator = access.authenticator();
        Credentials credentials = new Credentials(access.server(), headers);
        McpAuthentication result;
        try (InstanceSource.Handle handle = authenticator.instances().acquire()) {
            result = ((McpAuthenticator) handle.get()).authenticate(credentials);
        } catch (RuntimeException e) {
            failure(access, "Rejected a request to MCP Server '" + access.server() + "' because "
                    + authenticator.describe() + " failed: " + e.getClass().getName() + ": "
                    + LogText.clean(e.getMessage()));
            throw challenge(null, "invalid_token");
        }
        if (result == null) {
            failure(access, "Rejected a request to MCP Server '" + access.server() + "' because "
                    + authenticator.describe() + " returned null");
            throw challenge(null, "invalid_token");
        }
        if (credentials.ambiguity() != null) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + access.server() + "': "
                    + credentials.ambiguity());
            throw challenge(null, "invalid_token");
        }
        switch (result.outcome()) {
        case AUTHENTICATED:
            return Optional.of(new CremaCaller(server.application(),
                    new CallerPrincipal(result.name(), result.claims()), result.roles()));
        case REJECTED:
            LOG.fine(() -> "Rejected a request to MCP Server '" + access.server() + "': "
                    + authenticator.describe() + " rejected its credentials");
            throw challenge(null, "invalid_token");
        default:
            return Optional.empty();
        }
    }

    /**
     * Logs why a token was rejected for a reason that isn't the token's fault, such as unavailable keys.
     */
    private static void tokenFailure(McpServerAccess access, String reason) {
        failure(access, "Rejected a bearer token for MCP Server '" + access.server() + "' because " + reason);
    }

    /**
     * Logs a failure to authenticate that any client can trigger: as a warning without stack trace at most once
     * per minute and MCP Server, and else at {@code FINE}.
     */
    private static void failure(McpServerAccess access, String message) {
        if (access.failures().permit()) {
            LOG.warning(message + " (further such warnings are suppressed for a minute)");
        } else {
            LOG.fine(message);
        }
    }

    private McpServerAccess access(McpServerModel server) {
        McpServerAccess access = servers.get(server.application());
        if (access == null) {
            throw new IllegalArgumentException("Unknown MCP Server '" + server.wireName() + "'");
        }
        return access;
    }

    /**
     * A {@code 401} challenge; one without {@code scope} and {@code resource_metadata} if there is no OAuth
     * protection.
     */
    private static Rejection challenge(Protection protection, String error) {
        List<String> parameters = new ArrayList<>();
        if (error != null) {
            parameters.add("error=\"" + error + "\"");
        }
        if (protection != null) {
            if (!protection.scopes().isEmpty()) {
                parameters.add("scope=\"" + String.join(" ", protection.scopes()) + "\"");
            }
            parameters.add("resource_metadata=\"" + protection.resourceMetadataUrl() + "\"");
        }
        return new Rejection(401, Map.of(WWW_AUTHENTICATE,
                parameters.isEmpty() ? BEARER : BEARER + " " + String.join(", ", parameters)), null);
    }
}
