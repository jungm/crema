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

    private static final String ORIGIN_ALLOWED = "crema.origin.allowed";
    private static final String LIST_TTL_MS = "crema.cache.list-ttl-ms";
    private static final long DEFAULT_LIST_TTL_MS = 300_000;
    private static final String MAX_REQUEST_BYTES = "crema.max-request-bytes";
    private static final long DEFAULT_MAX_REQUEST_BYTES = 4L * 1024 * 1024;

    public CremaSettings {
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public static CremaSettings defaults() {
        return new CremaSettings(List.of(), DEFAULT_LIST_TTL_MS, DEFAULT_MAX_REQUEST_BYTES);
    }

    /**
     * Reads the settings.
     *
     * @throws IllegalArgumentException if {@code crema.cache.list-ttl-ms} isn't an integer {@code >= 0}, or
     *         {@code crema.max-request-bytes} isn't an integer {@code >= 1}
     */
    public static CremaSettings resolve(ConfigLookup config) {
        List<String> origins = config.get(ORIGIN_ALLOWED)
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        long ttl = config.get(LIST_TTL_MS).map(value -> ConfigValues.integer(LIST_TTL_MS, value, 0, "milliseconds"))
                .orElse(DEFAULT_LIST_TTL_MS);
        long maxRequestBytes = config.get(MAX_REQUEST_BYTES)
                .map(value -> ConfigValues.integer(MAX_REQUEST_BYTES, value, 1, "bytes"))
                .orElse(DEFAULT_MAX_REQUEST_BYTES);
        return new CremaSettings(origins, ttl, maxRequestBytes);
    }
}
