package io.github.jungm.crema.internal.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validation and copying of {@code _meta} maps and other collections held by the value types.
 */
final class Meta {

    private static final String LABEL = "[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?";
    private static final Pattern KEY = Pattern.compile(
            "(?:" + LABEL + "(?:\\." + LABEL + ")*/)?(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?");

    private Meta() {
    }

    /**
     * Checks a {@code _meta} key against the MCP key format: an optional prefix of dot-separated labels
     * followed by {@code /}, then a name that is empty or starts and ends with an alphanumeric character.
     */
    static String validKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid _meta key: '" + key + "'");
        }
        return key;
    }

    /**
     * An unmodifiable, insertion-ordered copy that permits {@code null} values.
     */
    static <V> Map<String, V> copy(Map<String, V> map) {
        return map.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    static <T> List<T> copy(List<T> list) {
        return List.copyOf(list);
    }

    static byte[] copy(byte[] data) {
        return Objects.requireNonNull(data, "data").clone();
    }
}
