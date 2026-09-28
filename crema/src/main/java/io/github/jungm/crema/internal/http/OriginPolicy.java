package io.github.jungm.crema.internal.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protects against DNS rebinding: a request without {@code Origin} passes, and a present {@code Origin} passes
 * only if it is a loopback origin ({@code http} or {@code https} with host {@code localhost}, {@code 127.0.0.1} or
 * {@code [::1]}, any port) or one of the allowed origins. An allowed origin {@code *} disables the check. The
 * {@code Host} header plays no part, since a rebinding attack controls it as well.
 */
public final class OriginPolicy {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final boolean any;
    private final Set<String> allowed;

    /**
     * @param allowed the configured origins, such as {@code https://app.example.com}
     */
    public OriginPolicy(List<String> allowed) {
        this.any = allowed.contains("*");
        this.allowed = allowed.stream().map(OriginPolicy::normalize).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @param origin the {@code Origin} header, or {@code null}
     */
    public boolean permits(String origin) {
        if (origin == null || any) {
            return true;
        }
        String normalized = normalize(origin);
        return allowed.contains(normalized) || isLoopback(normalized);
    }

    private static boolean isLoopback(String origin) {
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null
                    && LOOPBACK_HOSTS.contains(uri.getHost()) && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String normalize(String origin) {
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
