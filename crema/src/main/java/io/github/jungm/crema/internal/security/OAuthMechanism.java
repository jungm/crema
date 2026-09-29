package io.github.jungm.crema.internal.security;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWTClaimsSet;

import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * OAuth for a protected MCP Server: every request needs a bearer token that {@link TokenValidator} accepts, and the
 * caller and its roles come from the token. Without one the response is a {@code 401} challenge that points to the
 * Protected Resource Metadata; with an invalid one it is a {@code 401} with {@code error="invalid_token"}. Both
 * challenges carry the configured scopes, if any. A caller without the role a Feature needs gets a {@code 403} with
 * {@code error="insufficient_scope"}.
 */
public final class OAuthMechanism implements Mechanism {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer";

    private final Protection protection;
    private final TokenValidator validator;
    private final Rejection unauthenticated;
    private final Rejection invalid;

    /**
     * Lets through one warning per minute about failures to validate tokens that aren't the token's fault, such as
     * unavailable keys.
     */
    private final LogText.Throttle failures = new LogText.Throttle(1, TimeUnit.MINUTES);

    public OAuthMechanism(Protection protection) {
        this(protection, TokenValidator.Tuning.DEFAULT);
    }

    OAuthMechanism(Protection protection, TokenValidator.Tuning tuning) {
        this.protection = protection;
        this.validator = new TokenValidator(protection, tuning);
        this.unauthenticated = Challenges.bearer(protection, null);
        this.invalid = Challenges.bearer(protection, "invalid_token");
    }

    @Override
    public Optional<Caller> authenticate(McpServerModel server, Function<String, List<String>> headers) {
        List<String> authorization = headers.apply(AUTHORIZATION);
        if (authorization.isEmpty()) {
            return Optional.empty();
        }
        if (authorization.size() > 1) {
            Challenges.LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server()
                    + "' with more than one Authorization header");
            throw invalid;
        }
        String value = authorization.get(0).strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase(BEARER)) {
            return Optional.empty();
        }
        String token = space < 0 ? "" : value.substring(space + 1).stripLeading();
        return Optional.of(validate(server, token).orElseThrow(() -> invalid));
    }

    @Override
    public Rejection unauthenticated() {
        return unauthenticated;
    }

    @Override
    public Rejection forbidden() {
        return new Rejection(403, Map.of(Challenges.WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", resource_metadata=\"" + protection.resourceMetadataUrl()
                        + "\""), null);
    }

    /**
     * The Protected Resource Metadata, with {@code scopes_supported} if scopes are configured. It doesn't depend on
     * the request.
     */
    @Override
    public Optional<JsonObject> resourceMetadata() {
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
        try {
            validator.close();
        } catch (IOException | RuntimeException e) {
            Challenges.LOG.log(Level.FINE, "Couldn't close the key source of MCP Server '" + protection.server()
                    + "'", e);
        }
    }

    private Optional<Caller> validate(McpServerModel server, String token) {
        if (token.isEmpty()) {
            Challenges.LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server()
                    + "': empty bearer token");
            return Optional.empty();
        }
        if (!Credentials.B64TOKEN.matcher(token).matches()) {
            Challenges.LOG.fine(() -> "Rejected a request to MCP Server '" + protection.server() + "': the bearer "
                    + "token has characters outside the RFC 6750 b64token syntax, such as whitespace");
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = validator.validate(token);
        } catch (RateLimitReachedException e) {
            Challenges.LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server()
                    + "': no key of issuer " + protection.issuer() + " matches it, and the keys were retrieved too "
                    + "recently to retrieve them again");
            return Optional.empty();
        } catch (KeySourceException e) {
            failure("the keys of issuer " + protection.issuer() + " aren't available: "
                    + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (ParseException | BadJOSEException | JOSEException e) {
            Challenges.LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server()
                    + "' (resource " + protection.resource() + "): " + LogText.clean(e.getMessage()));
            return Optional.empty();
        } catch (RuntimeException e) {
            failure("validating it failed: " + e.getClass().getName() + ": " + LogText.clean(e.getMessage()));
            return Optional.empty();
        }
        Map<String, Object> values = claims.getClaims();
        Object name = CremaCaller.claim(values, protection.principalClaim());
        if (!(name instanceof String principal) || principal.isEmpty()) {
            Challenges.LOG.fine(() -> "Rejected a bearer token for MCP Server '" + protection.server()
                    + "': its principal claim " + protection.principalClaim() + " isn't a non-empty string");
            return Optional.empty();
        }
        return Optional.of(new CremaCaller(server.application(), new CallerPrincipal(principal, values),
                CremaCaller.roles(CremaCaller.claim(values, protection.rolesClaim()))));
    }

    /**
     * Logs why a token was rejected for a reason that isn't the token's fault.
     */
    private void failure(String reason) {
        Challenges.failure(failures, "Rejected a bearer token for MCP Server '" + protection.server() + "' because "
                + reason);
    }
}
