package io.github.jungm.crema.internal.config;

import java.util.Arrays;
import java.util.List;

/**
 * Settings that apply to all MCP Servers of an application.
 *
 * @param allowedOrigins the origins of {@code crema.origin.allowed}, which may contain {@code *}
 * @param listTtlMs the {@code ttlMs} of cacheable list results, from {@code crema.cache.list-ttl-ms}
 */
public record CremaSettings(List<String> allowedOrigins, long listTtlMs) {

    public static final String ORIGIN_ALLOWED = "crema.origin.allowed";
    public static final String LIST_TTL_MS = "crema.cache.list-ttl-ms";
    public static final long DEFAULT_LIST_TTL_MS = 300_000;

    public CremaSettings {
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public static CremaSettings defaults() {
        return new CremaSettings(List.of(), DEFAULT_LIST_TTL_MS);
    }

    /**
     * Reads the settings.
     *
     * @throws IllegalArgumentException if {@code crema.cache.list-ttl-ms} isn't an integer {@code >= 0}
     */
    public static CremaSettings resolve(ConfigLookup config) {
        List<String> origins = config.get(ORIGIN_ALLOWED)
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        long ttl = config.get(LIST_TTL_MS).map(CremaSettings::ttl).orElse(DEFAULT_LIST_TTL_MS);
        return new CremaSettings(origins, ttl);
    }

    private static long ttl(String value) {
        long ttl;
        try {
            ttl = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            ttl = -1;
        }
        if (ttl < 0) {
            throw new IllegalArgumentException(
                    LIST_TTL_MS + " must be an integer number of milliseconds >= 0, but is '" + value + "'");
        }
        return ttl;
    }
}
