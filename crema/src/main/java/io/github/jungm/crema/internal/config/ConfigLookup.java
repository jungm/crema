package io.github.jungm.crema.internal.config;

import java.util.Map;
import java.util.Optional;

/**
 * Read access to Crema's configuration keys. MicroProfile Config is the only configuration source, and it is
 * optional: {@link #of(ClassLoader)} yields an empty lookup when it isn't available.
 */
@FunctionalInterface
public interface ConfigLookup {

    /**
     * The value of a key; empty when the key is unset or its value is blank.
     */
    Optional<String> get(String key);

    /**
     * Whether values come from MicroProfile Config.
     */
    default boolean isMicroProfile() {
        return false;
    }

    /**
     * A lookup that knows no keys.
     */
    static ConfigLookup none() {
        return key -> Optional.empty();
    }

    /**
     * A lookup backed by a map, for tests.
     */
    static ConfigLookup of(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key)).filter(value -> !value.isBlank());
    }

    /**
     * The MicroProfile Config of the given class loader when the MicroProfile Config API and an implementation
     * are present, else {@link #none()}.
     */
    static ConfigLookup of(ClassLoader classLoader) {
        try {
            Class.forName("org.eclipse.microprofile.config.ConfigProvider", false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return none();
        }
        return MicroProfileConfigLookup.create(classLoader).orElseGet(ConfigLookup::none);
    }
}
