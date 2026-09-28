package io.github.jungm.crema.internal.config;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * {@link ConfigLookup} backed by MicroProfile Config. This is the only class that references MicroProfile
 * Config types; it is loaded only after {@link ConfigLookup#of(ClassLoader)} has found the API.
 */
final class MicroProfileConfigLookup implements ConfigLookup {

    private static final Logger LOG = Logger.getLogger(MicroProfileConfigLookup.class.getName());

    private final Config config;

    private MicroProfileConfigLookup(Config config) {
        this.config = config;
    }

    static Optional<ConfigLookup> create(ClassLoader classLoader) {
        try {
            return Optional.of(new MicroProfileConfigLookup(ConfigProvider.getConfig(classLoader)));
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.FINE, "MicroProfile Config API present but not usable", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> get(String key) {
        return config.getOptionalValue(key, String.class).filter(value -> !value.isBlank());
    }

    @Override
    public boolean isMicroProfile() {
        return true;
    }
}
