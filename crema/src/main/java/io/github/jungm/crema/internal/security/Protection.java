package io.github.jungm.crema.internal.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.ServerSettings;

/**
 * The configuration of a protected MCP Server: one whose {@code McpApplication} subclass carries
 * {@code @RolesAllowed} or {@code @DenyAll}. It comes from MicroProfile Config, under the MCP Server's key prefix.
 *
 * @param server the MCP Server's name for messages
 * @param issuer the Authorization Server's issuer identifier, which tokens' {@code iss} must equal
 * @param jwksUri the Authorization Server's JWK set URL, or {@code null} to read it from the issuer's metadata
 * @param resource the Resource Identifier: the MCP Endpoint's public URL, which tokens' {@code aud} must contain
 * @param rolesClaim the dotted path of the claim that holds the caller's roles
 * @param principalClaim the dotted path of the claim that holds the caller's name
 * @param clockSkewSeconds the tolerated clock skew for {@code exp} and {@code nbf}
 */
public record Protection(String server, String issuer, URI jwksUri, String resource, String rolesClaim,
        String principalClaim, int clockSkewSeconds) {

    private static final String ISSUER = "issuer";
    private static final String JWKS_URI = "jwks-uri";
    private static final String ROLES_CLAIM = "roles-claim";
    private static final String PRINCIPAL_CLAIM = "principal-claim";
    private static final String CLOCK_SKEW_SECONDS = "clock-skew-seconds";
    private static final String RESOURCE = "resource";

    /**
     * The path of the Protected Resource Metadata (RFC 9728), relative to the Resource Identifier.
     */
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private static final String DEFAULT_ROLES_CLAIM = "groups";
    private static final String DEFAULT_PRINCIPAL_CLAIM = "sub";
    private static final int DEFAULT_CLOCK_SKEW_SECONDS = 60;

    private static final Logger LOG = Logger.getLogger(Protection.class.getName());

    /**
     * Resolves the protection of the MCP Server that an {@code McpApplication} subclass declares.
     *
     * @param problems collects deployment problems
     * @return the protection, or empty if the MCP Server isn't protected or is misconfigured
     */
    public static Optional<Protection> resolve(Class<?> application, ServerSettings settings, ConfigLookup config,
            List<String> problems) {
        String prefix = ServerSettings.keyPrefix(settings.name());
        Optional<AccessRule> rule = AccessRule.ofApplication(application, problems);
        if (rule.isEmpty() || !rule.get().restricts()) {
            config.get(prefix + ISSUER).ifPresent(issuer -> LOG.warning("MCP Server '" + settings.wireName()
                    + "' isn't protected, so " + prefix + ISSUER + " has no effect; put @RolesAllowed or @DenyAll "
                    + "on McpApplication " + application.getName() + " to require bearer tokens"));
            return Optional.empty();
        }
        String where = "McpApplication " + application.getName() + " (MCP Server '" + settings.wireName() + "')";
        if (!config.isMicroProfile()) {
            problems.add(where + " is protected by @RolesAllowed or @DenyAll, which requires MicroProfile Config to "
                    + "configure " + prefix + ISSUER + ", but MicroProfile Config isn't available");
            return Optional.empty();
        }
        int before = problems.size();
        Optional<String> issuer = config.get(prefix + ISSUER).map(String::trim);
        if (issuer.isEmpty()) {
            problems.add(where + " is protected by @RolesAllowed or @DenyAll, but " + prefix + ISSUER
                    + " isn't set; set it to the issuer identifier of the Authorization Server");
        } else {
            checkUrl(issuer.get(), prefix + ISSUER, where, true, problems);
        }
        URI jwksUri = config.get(prefix + JWKS_URI).map(String::trim)
                .map(value -> checkUrl(value, prefix + JWKS_URI, where, false, problems)).orElse(null);
        String resource = settings.resource() == null ? "" : settings.resource().trim();
        if (resource.isEmpty()) {
            problems.add(where + " is protected by @RolesAllowed or @DenyAll, but " + prefix + RESOURCE
                    + " isn't set; set it to the MCP Endpoint's public URL, which tokens must be issued for");
        } else {
            checkResource(resource, prefix + RESOURCE, where, problems);
        }
        int clockSkew = config.get(prefix + CLOCK_SKEW_SECONDS).map(value -> {
            try {
                int seconds = Integer.parseInt(value.trim());
                if (seconds >= 0) {
                    return seconds;
                }
            } catch (NumberFormatException e) {
                // reported below
            }
            problems.add(where + ": " + prefix + CLOCK_SKEW_SECONDS + " must be an integer number of seconds >= 0, "
                    + "but is '" + value + "'");
            return DEFAULT_CLOCK_SKEW_SECONDS;
        }).orElse(DEFAULT_CLOCK_SKEW_SECONDS);
        if (problems.size() > before) {
            return Optional.empty();
        }
        return Optional.of(new Protection(settings.wireName(), issuer.get(), jwksUri, resource,
                config.get(prefix + ROLES_CLAIM).map(String::trim).orElse(DEFAULT_ROLES_CLAIM),
                config.get(prefix + PRINCIPAL_CLAIM).map(String::trim).orElse(DEFAULT_PRINCIPAL_CLAIM),
                clockSkew));
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
