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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWTClaimsSet;

import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.model.ApplicationMethod;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Decides who may use the MCP Servers of an application and their Features: role checks with
 * {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll} for every MCP Server, and bearer tokens for
 * protected MCP Servers.
 * <ul>
 * <li>On an open MCP Server the caller and its roles are the Runtime's.</li>
 * <li>On a protected MCP Server every request needs a bearer token that {@link TokenValidator} accepts, and the
 * caller and its roles come from the token. Without one the response is a {@code 401} challenge that points to the
 * Protected Resource Metadata; with an invalid one it is a {@code 401} with {@code error="invalid_token"}. Both
 * challenges carry the configured scopes, if any. Why a token was rejected is logged, never returned.</li>
 * </ul>
 */
public final class CremaAccessPolicy implements Closeable {

    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String BEARER = "Bearer";

    /**
     * The syntax of RFC 6750 §2.1 {@code b64token}: the only characters a bearer token may have. A value with
     * whitespace or anything else, such as two tokens or two joined {@code Authorization} headers, is invalid.
     * This checks the header's syntax only; the token itself is parsed and validated by Nimbus.
     */
    private static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

    private static final Logger LOG = Logger.getLogger(CremaAccessPolicy.class.getName());

    private final Map<Class<?>, McpServerAccess> servers = new LinkedHashMap<>();

    /**
     * @param failures lets through one warning per minute about failures to validate tokens that aren't the
     *        token's fault, such as unavailable keys
     */
    private record McpServerAccess(Protection protection, TokenValidator validator,
            Map<ApplicationMethod, AccessRule> rules, boolean isPrivate, LogText.Throttle failures) {
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
     * @param protections the protection of each protected MCP Server, by {@code McpApplication} subclass; MCP
     *        Servers without one are open
     */
    public static Result create(List<McpServerModel> servers, Map<Class<?>, Protection> protections) {
        return create(servers, protections, TokenValidator.Tuning.DEFAULT);
    }

    static Result create(List<McpServerModel> servers, Map<Class<?>, Protection> protections,
            TokenValidator.Tuning tuning) {
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
            TokenValidator validator = protection == null ? null : new TokenValidator(protection, tuning);
            policy.servers.put(server.application(),
                    new McpServerAccess(protection, validator, rules, protection != null || restricted,
                            new LogText.Throttle(1, TimeUnit.MINUTES)));
        }
        return new Result(policy, List.copyOf(problems));
    }

    /**
     * Checks a request before any MCP processing, after the {@code Origin} check, and determines the caller that
     * the rest of the request is processed for.
     *
     * @param caller the caller according to the Runtime
     * @param authorization the values of the request's {@code Authorization} header; empty when absent
     * @return the caller to process the request for: {@code caller} on an open MCP Server, the token's caller on a
     *         protected one
     * @throws Rejection a {@code 401} challenge
     */
    public Caller authenticate(McpServerModel server, Caller caller, List<String> authorization) {
        McpServerAccess access = access(server);
        if (access.protection() == null) {
            return caller;
        }
        Protection protection = access.protection();
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
        if (access.protection() != null
                && !(caller instanceof TokenCaller tokenCaller && tokenCaller.isFor(server.application()))) {
            return false;
        }
        return access.rules().get(method).permits(caller);
    }

    /**
     * The rejection of a request that invokes a Feature the caller may not use: {@code 403}, with an
     * {@code insufficient_scope} challenge on a protected MCP Server.
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
     * caller is authenticated, by the Runtime or by a token.
     *
     * @param caller a caller {@link #authenticate admitted} for this MCP Server
     */
    public boolean isPrivate(McpServerModel server, Caller caller) {
        return access(server).isPrivate() || caller instanceof TokenCaller
                || CallerPrincipal.safely(caller::principal) != null;
    }

    /**
     * The Protected Resource Metadata (RFC 9728) of a protected MCP Server, with {@code scopes_supported} if
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
        if (!B64TOKEN.matcher(token).matches()) {
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
            failure(access, "the keys of issuer " + protection.issuer() + " aren't available: "
                    + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (ParseException | BadJOSEException | JOSEException e) {
            LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server() + "' (resource "
                    + protection.resource() + "): " + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (RuntimeException e) {
            failure(access, "validating it failed: " + e.getClass().getName() + ": " + LogText.clean(e.getMessage()));
            return Optional.empty();
        }
        Map<String, Object> values = claims.getClaims();
        Object name = TokenCaller.claim(values, protection.principalClaim());
        if (!(name instanceof String principal) || principal.isEmpty()) {
            LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server()
                    + "': its principal claim " + protection.principalClaim() + " isn't a non-empty string");
            return Optional.empty();
        }
        return Optional.of(new TokenCaller(server.application(), new CallerPrincipal(principal, values),
                TokenCaller.roles(TokenCaller.claim(values, protection.rolesClaim()))));
    }

    /**
     * Logs why a token was rejected for a reason that isn't the token's fault: as a warning without stack trace
     * at most once per minute and MCP Server, since any client can trigger it, and else at {@code FINE}.
     */
    private static void failure(McpServerAccess access, String reason) {
        String message = "Rejected a bearer token for MCP Server '" + access.protection().server() + "' because "
                + reason;
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

    private static Rejection challenge(Protection protection, String error) {
        return new Rejection(401, Map.of(WWW_AUTHENTICATE, BEARER + " "
                + (error == null ? "" : "error=\"" + error + "\", ")
                + (protection.scopes().isEmpty() ? "" : "scope=\"" + String.join(" ", protection.scopes()) + "\", ")
                + "resource_metadata=\"" + protection.resourceMetadataUrl() + "\""), null);
    }
}
