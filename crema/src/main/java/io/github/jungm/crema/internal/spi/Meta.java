package io.github.jungm.crema.internal.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Copying of {@code _meta} maps and other collections held by the value types.
 */
final class Meta {

    private Meta() {
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
