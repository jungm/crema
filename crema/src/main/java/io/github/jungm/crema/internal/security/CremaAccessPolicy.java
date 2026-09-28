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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWTClaimsSet;

import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.FeatureMethod;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Json;
import jakarta.json.JsonObject;

/**
 * Crema's {@link AccessPolicy}: role checks with {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll}
 * for every MCP Server, and bearer tokens for protected MCP Servers.
 * <ul>
 * <li>On an open MCP Server the caller and its roles are the Runtime's.</li>
 * <li>On a protected MCP Server every request needs a bearer token that {@link TokenValidator} accepts, and the
 * caller and its roles come from the token. Without one the response is a {@code 401} challenge that points to the
 * Protected Resource Metadata; with an invalid one it is a {@code 401} with {@code error="invalid_token"}. Why a
 * token was rejected is logged, never returned.</li>
 * </ul>
 */
public final class CremaAccessPolicy implements AccessPolicy, Closeable {

    static final String AUTHORIZATION = "Authorization";
    static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private static final Logger LOG = Logger.getLogger(CremaAccessPolicy.class.getName());

    private final Map<Class<?>, ServerAccess> servers = new LinkedHashMap<>();

    /**
     * @param failures lets through one warning per minute about failures to validate tokens that aren't the
     *        token's fault, such as unavailable keys
     */
    private record ServerAccess(Protection protection, TokenValidator validator,
            Map<FeatureMethod, AccessRule> rules, boolean isPrivate, LogText.Throttle failures) {
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
     * @param protections the protection of each protected MCP Server, by {@code McpApplication} subclass
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
            Map<FeatureMethod, AccessRule> rules = new IdentityHashMap<>();
            List<String> found = new ArrayList<>();
            boolean restricted = false;
            for (Feature feature : server.features()) {
                AccessRule rule = AccessRule.of(feature.method().method(), application, found);
                rules.put(feature.method(), rule);
                restricted |= rule.restricts();
            }
            problems.addAll(found);
            Protection protection = protections.get(server.application());
            TokenValidator validator = protection == null ? null : new TokenValidator(protection, tuning);
            policy.servers.put(server.application(),
                    new ServerAccess(protection, validator, rules, protection != null || restricted,
                            new LogText.Throttle(1, TimeUnit.MINUTES)));
        }
        return new Result(policy, List.copyOf(problems));
    }

    @Override
    public Admission authenticate(McpServerModel server, Caller caller) {
        ServerAccess access = access(server);
        if (access.protection() == null) {
            return new Admitted(caller);
        }
        if (caller instanceof TokenCaller tokenCaller && tokenCaller.isFor(server.application())) {
            return new Admitted(caller);
        }
        String metadata = access.protection().resourceMetadataUrl();
        List<String> authorization = caller.header(AUTHORIZATION);
        if (authorization.isEmpty()) {
            return challenge(metadata, null);
        }
        if (authorization.size() > 1) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + access.protection().server()
                    + "' with more than one Authorization header");
            return challenge(metadata, "invalid_token");
        }
        String value = authorization.get(0).strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase(TokenSecurityContext.BEARER)) {
            return challenge(metadata, null);
        }
        String token = space < 0 ? "" : value.substring(space + 1).strip();
        return validate(server, access, caller, token)
                .<Admission>map(Admitted::new)
                .orElseGet(() -> challenge(metadata, "invalid_token"));
    }

    @Override
    public boolean permits(McpServerModel server, Feature feature, Caller caller) {
        ServerAccess access = access(server);
        if (access.protection() != null
                && !(caller instanceof TokenCaller tokenCaller && tokenCaller.isFor(server.application()))) {
            return false;
        }
        AccessRule rule = access.rules().get(feature.method());
        if (rule == null) {
            rule = AccessRule.of(feature.method().method(),
                    AccessRule.ofApplication(server.application(), new ArrayList<>()), new ArrayList<>());
        }
        return rule.permits(caller);
    }

    @Override
    public Rejection forbidden(McpServerModel server, Caller caller) {
        ServerAccess access = access(server);
        if (access.protection() == null) {
            return new Rejection(403, Map.of());
        }
        return new Rejection(403, Map.of(WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", resource_metadata=\""
                        + access.protection().resourceMetadataUrl() + "\""));
    }

    @Override
    public boolean isPrivate(McpServerModel server) {
        return access(server).isPrivate();
    }

    @Override
    public Optional<JsonObject> resourceMetadata(McpServerModel server) {
        ServerAccess access = access(server);
        if (access.protection() == null) {
            return Optional.empty();
        }
        return Optional.of(Json.object()
                .add("resource", access.protection().resource())
                .add("authorization_servers", Json.FACTORY.createArrayBuilder().add(access.protection().issuer()))
                .add("bearer_methods_supported", Json.FACTORY.createArrayBuilder().add("header"))
                .build());
    }

    /**
     * Stops the background retrieval of keys.
     */
    @Override
    public void close() {
        for (ServerAccess access : servers.values()) {
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

    private Optional<Caller> validate(McpServerModel server, ServerAccess access, Caller caller, String token) {
        Protection protection = access.protection();
        String resource = protection.resource();
        if (token.isEmpty()) {
            LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server() + "': empty bearer token");
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = access.validator().validate(token, resource);
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
                    + resource + "): " + LogText.clean(e.getMessage()));
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
        return Optional.of(new TokenCaller(server.application(), caller, new CallerPrincipal(principal, values),
                TokenCaller.roles(TokenCaller.claim(values, protection.rolesClaim()))));
    }

    /**
     * Logs why a token was rejected for a reason that isn't the token's fault: as a warning without stack trace
     * at most once per minute and MCP Server, since any client can trigger it, and else at {@code FINE}.
     */
    private static void failure(ServerAccess access, String reason) {
        String message = "Rejected a bearer token for MCP Server '" + access.protection().server() + "' because "
                + reason;
        if (access.failures().permit()) {
            LOG.warning(message + " (further such warnings are suppressed for a minute)");
        } else {
            LOG.fine(message);
        }
    }

    private ServerAccess access(McpServerModel server) {
        ServerAccess access = servers.get(server.application());
        if (access == null) {
            throw new IllegalArgumentException("Unknown MCP Server '" + server.settings().wireName() + "'");
        }
        return access;
    }

    private static Rejected challenge(String metadataUrl, String error) {
        return new Rejected(new Rejection(401, Map.of(WWW_AUTHENTICATE, "Bearer "
                + (error == null ? "" : "error=\"" + error + "\", ") + "resource_metadata=\"" + metadataUrl + "\"")));
    }
}
