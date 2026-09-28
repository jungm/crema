package io.github.jungm.crema;

import java.util.Map;
import java.util.Set;

import io.github.jungm.crema.internal.http.McpEndpoint;
import io.github.jungm.crema.internal.http.McpEndpointFilter;
import jakarta.ws.rs.core.Application;

/**
 * Declares one MCP Server, reachable at the {@code @ApplicationPath} of the concrete subclass:
 *
 * <pre>
 * &#64;ApplicationPath("mcp")
 * &#64;McpServerInfo(title = "Order Service")
 * public class OrderMcp extends McpApplication {}
 * </pre>
 * <p>
 * Only Crema's own resources and providers serve MCP traffic. Subclasses must not declare any methods, in particular
 * not override {@link #getClasses()}, {@link #getSingletons()} or {@link #getProperties()}; deployment fails if they
 * do. Features are exposed only through an {@code McpApplication}.
 *
 * @see McpServerInfo
 */
public abstract class McpApplication extends Application {

    /**
     * The {@link #getProperties() property} that holds the {@code McpApplication} subclass serving a request.
     */
    public static final String APPLICATION_PROPERTY = "io.github.jungm.crema.application";

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpEndpoint.class, McpEndpointFilter.class);
    }

    @Override
    public Set<Object> getSingletons() {
        return Set.of();
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of(APPLICATION_PROPERTY, getClass());
    }
}
