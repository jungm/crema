package io.github.jungm.crema.internal.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.ConfigValues;
import io.github.jungm.crema.internal.config.McpServerSettings;

/**
 * The OAuth configuration of a protected MCP Server whose {@code authenticator} is {@code oauth}. It comes from
 * MicroProfile Config, under the MCP Server's key prefix.
 *
 * @param server the MCP Server's name for messages
 * @param issuer the Authorization Server's issuer identifier, which tokens' {@code iss} must equal
 * @param jwksUri the Authorization Server's JWK set URL, or {@code null} to read it from the issuer's metadata
 * @param resource the Resource Identifier: the MCP Endpoint's public URL, which tokens' {@code aud} must contain
 * @param rolesClaim the dotted path of the claim that holds the caller's roles
 * @param principalClaim the dotted path of the claim that holds the caller's name
 * @param clockSkewSeconds the tolerated clock skew for {@code exp} and {@code nbf}
 * @param scopes the OAuth scopes that MCP Clients should request, as {@code scopes_supported} in the Protected
 *        Resource Metadata and {@code scope} in {@code 401} challenges; empty for none
 */
public record Protection(String server, String issuer, URI jwksUri, String resource, String rolesClaim,
        String principalClaim, int clockSkewSeconds, List<String> scopes) {

    static final String ISSUER = "issuer";
    private static final String JWKS_URI = "jwks-uri";
    private static final String ROLES_CLAIM = "roles-claim";
    private static final String PRINCIPAL_CLAIM = "principal-claim";
    private static final String CLOCK_SKEW_SECONDS = "clock-skew-seconds";
    private static final String RESOURCE = "resource";
    private static final String SCOPES = "scopes";

    /**
     * The syntax of an RFC 6749 §3.3 {@code scope-token}, without the comma that separates scopes in the
     * configuration.
     */
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x5B\\x5D-\\x7E]+");

    /**
     * The path of the Protected Resource Metadata (RFC 9728), relative to the Resource Identifier.
     */
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private static final String DEFAULT_ROLES_CLAIM = "groups";
    private static final String DEFAULT_PRINCIPAL_CLAIM = "sub";
    private static final int DEFAULT_CLOCK_SKEW_SECONDS = 60;

    public Protection {
        scopes = List.copyOf(scopes);
    }

    /**
     * Whether an {@code McpApplication} subclass declares a protected MCP Server.
     */
    public static boolean isProtected(Class<?> application) {
        return AccessRule.ofApplication(application, new ArrayList<>()).map(AccessRule::restricts).orElse(false);
    }

    /**
     * Resolves the OAuth configuration of an MCP Server whose {@code authenticator} is {@code oauth}.
     *
     * @param where names the MCP Server in problems
     * @param problems collects deployment problems
     * @return the protection, or empty if it is misconfigured
     */
    static Optional<Protection> resolve(McpServerSettings settings, ConfigLookup config, String where,
            List<String> problems) {
        String prefix = McpServerSettings.keyPrefix(settings.name());
        Optional<String> issuer = config.get(prefix + ISSUER).map(String::trim);
        if (issuer.isEmpty()) {
            problems.add(where + " uses OAuth, but " + prefix + ISSUER
                    + " isn't set; set it to the issuer identifier of the Authorization Server");
            return Optional.empty();
        }
        int before = problems.size();
        checkUrl(issuer.get(), prefix + ISSUER, where, true, problems);
        URI jwksUri = config.get(prefix + JWKS_URI).map(String::trim)
                .map(value -> checkUrl(value, prefix + JWKS_URI, where, false, problems)).orElse(null);
        String resource = settings.resource() == null ? "" : settings.resource().trim();
        if (resource.isEmpty()) {
            problems.add(where + " uses OAuth, but " + prefix + RESOURCE
                    + " isn't set; set it to the MCP Endpoint's public URL, which tokens must be issued for");
        } else {
            checkResource(resource, prefix + RESOURCE, where, problems);
        }
        int clockSkew = config.get(prefix + CLOCK_SKEW_SECONDS).map(value -> {
            try {
                return (int) Math.min(Integer.MAX_VALUE,
                        ConfigValues.integer(prefix + CLOCK_SKEW_SECONDS, value, 0, "seconds"));
            } catch (IllegalArgumentException e) {
                problems.add(where + ": " + e.getMessage());
                return DEFAULT_CLOCK_SKEW_SECONDS;
            }
        }).orElse(DEFAULT_CLOCK_SKEW_SECONDS);
        List<String> scopes = config.get(prefix + SCOPES)
                .map(value -> scopes(value, prefix + SCOPES, where, problems)).orElse(List.of());
        if (problems.size() > before) {
            return Optional.empty();
        }
        return Optional.of(new Protection(settings.wireName(), issuer.get(), jwksUri, resource,
                config.get(prefix + ROLES_CLAIM).map(String::trim).orElse(DEFAULT_ROLES_CLAIM),
                config.get(prefix + PRINCIPAL_CLAIM).map(String::trim).orElse(DEFAULT_PRINCIPAL_CLAIM),
                clockSkew, scopes));
    }

    /**
     * The URL of the Protected Resource Metadata, {@code <resource>/.well-known/oauth-protected-resource}: where
     * the MCP Endpoint serves it, since the Resource Identifier is the MCP Endpoint's public URL.
     */
    public String resourceMetadataUrl() {
        String base = resource;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + METADATA_PATH;
    }

    /**
     * Whether a URL may be used to fetch the Authorization Server's metadata or keys: {@code https}, or
     * {@code http} to a loopback host.
     */
    static boolean isFetchable(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || scheme.equals("http") && Loopback.isHost(uri.getHost());
    }

    private static URI checkUrl(String value, String key, String where, boolean issuer, List<String> problems) {
        try {
            URI uri = new URI(value);
            if (!isFetchable(uri) || uri.getRawFragment() != null || issuer && uri.getRawQuery() != null) {
                problems.add(where + ": " + key + " must be an https URL (http only for localhost)"
                        + (issuer ? " without query or fragment" : " without fragment") + ", but is '" + value
                        + "'");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            problems.add(where + ": " + key + " isn't a valid URL: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a comma-separated list of scopes, ignoring whitespace around each one and duplicates.
     */
    private static List<String> scopes(String value, String key, String where, List<String> problems) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : value.split(",")) {
            String trimmed = scope.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!SCOPE_TOKEN.matcher(trimmed).matches()) {
                problems.add(where + ": " + key + " must be a comma-separated list of OAuth scopes without "
                        + "whitespace, '\"' or '\\', but contains '" + trimmed + "'");
            } else {
                scopes.add(trimmed);
            }
        }
        return List.copyOf(scopes);
    }

    private static void checkResource(String value, String key, String where, List<String> problems) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !scheme.equals("https") && !scheme.equals("http")) {
                problems.add(where + ": " + key + " must be the MCP Endpoint's absolute http(s) URL without "
                        + "user info, query or fragment, but is '" + value + "'");
            }
        } catch (URISyntaxException e) {
            problems.add(where + ": " + key + " isn't a valid URL: " + e.getMessage());
        }
    }
}
