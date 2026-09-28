package io.github.jungm.crema.it.security.app;

import jakarta.ws.rs.core.Application;

import java.util.Set;

/**
 * Mirrors the shape of Crema's {@code McpApplication}: an abstract {@link Application} whose final
 * {@link #getClasses()} registers only its own resources, subclassed by an application class that declares nothing.
 */
public abstract class McpApplicationBase extends Application {

    @Override
    public final Set<Class<?>> getClasses() {
        return Set.of(WhoAmIResource.class);
    }
}
