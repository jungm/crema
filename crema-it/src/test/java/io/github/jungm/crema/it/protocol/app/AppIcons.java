package io.github.jungm.crema.it.protocol.app;

import java.util.List;
import java.util.Locale;

import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;

/** Not a CDI bean: Crema instantiates it reflectively. */
public class AppIcons implements IconProvider {

    @Override
    public List<Icon> getIcons(FeatureType type, String name) {
        String kind = type == null ? "server" : type.name().toLowerCase(Locale.ROOT);
        return List.of(Icon.builder("https://example.com/icons/" + kind + "/" + name + ".png")
                .setMimeType("image/png").addSize(48, 48).setTheme(Icon.Theme.LIGHT).build());
    }
}
