package io.github.jungm.crema.internal.model;

import java.util.List;

import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;

/**
 * Obtains the icons of a Feature or MCP Server from an {@link IconProvider}.
 */
@FunctionalInterface
public interface IconLookup {

    /**
     * @param type the Feature type, or {@code null} for an MCP Server
     * @param name the Feature or MCP Server name
     */
    List<Icon> icons(Class<? extends IconProvider> provider, FeatureType type, String name);

    /**
     * Instantiates providers through their no-argument constructor.
     */
    static IconLookup reflective() {
        return (provider, type, name) -> {
            try {
                var constructor = provider.getDeclaredConstructor();
                constructor.trySetAccessible();
                List<Icon> icons = constructor.newInstance().getIcons(type, name);
                return icons == null ? List.of() : icons;
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("IconProvider " + provider.getName()
                        + " is neither a CDI bean nor instantiable through a no-argument constructor", e);
            }
        };
    }
}
