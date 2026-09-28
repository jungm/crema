package io.github.jungm.crema.internal.config;

import java.util.Map;
import java.util.Optional;

/**
 * {@link ConfigLookup}s backed by maps. Blank values count as unset, as with MicroProfile Config.
 */
public final class MapConfig {

    private MapConfig() {
    }

    public static ConfigLookup of(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key)).filter(value -> !value.isBlank());
    }
}
