package io.github.jungm.crema.internal.config;

import java.util.Arrays;
import java.util.List;

/**
 * Settings that apply to all MCP Servers of an application.
 *
 * @param allowedOrigins the origins of {@code crema.origin.allowed}, which may contain {@code *}
 * @param listTtlMs the {@code ttlMs} of cacheable list results, from {@code crema.cache.list-ttl-ms}
 * @param maxRequestBytes the largest request body accepted, from {@code crema.max-request-bytes}
 */
public record CremaSettings(List<String> allowedOrigins, long listTtlMs, long maxRequestBytes) {

    public static final String ORIGIN_ALLOWED = "crema.origin.allowed";
    public static final String LIST_TTL_MS = "crema.cache.list-ttl-ms";
    public static final long DEFAULT_LIST_TTL_MS = 300_000;
    public static final String MAX_REQUEST_BYTES = "crema.max-request-bytes";
    public static final long DEFAULT_MAX_REQUEST_BYTES = 4L * 1024 * 1024;

    public CremaSettings {
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    /**
     * Settings with the default {@code crema.max-request-bytes}.
     */
    public CremaSettings(List<String> allowedOrigins, long listTtlMs) {
        this(allowedOrigins, listTtlMs, DEFAULT_MAX_REQUEST_BYTES);
    }

    public static CremaSettings defaults() {
        return new CremaSettings(List.of(), DEFAULT_LIST_TTL_MS);
    }

    /**
     * Reads the settings.
     *
     * @throws IllegalArgumentException if {@code crema.cache.list-ttl-ms} isn't an integer {@code >= 0}, or
     *         {@code crema.max-request-bytes} isn't an integer {@code > 0}
     */
    public static CremaSettings resolve(ConfigLookup config) {
        List<String> origins = config.get(ORIGIN_ALLOWED)
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        long ttl = config.get(LIST_TTL_MS).map(CremaSettings::ttl).orElse(DEFAULT_LIST_TTL_MS);
        long maxRequestBytes = config.get(MAX_REQUEST_BYTES).map(CremaSettings::maxRequestBytes)
                .orElse(DEFAULT_MAX_REQUEST_BYTES);
        return new CremaSettings(origins, ttl, maxRequestBytes);
    }

    private static long maxRequestBytes(String value) {
        long bytes;
        try {
            bytes = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            bytes = 0;
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException(
                    MAX_REQUEST_BYTES + " must be an integer number of bytes > 0, but is '" + value + "'");
        }
        return bytes;
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
