package io.github.jungm.crema;

import java.util.Map;
import java.util.Set;

import io.github.jungm.crema.internal.http.McpEndpoint;
import io.github.jungm.crema.internal.http.McpEndpointFilter;
import io.github.jungm.crema.internal.http.ResourceMetadataEndpoint;
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
 * <p>
 * Crema enforces {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll} on Feature and Completion Methods:
 * the annotation on the method wins over the one on its declaring class, which wins over the one on the
 * {@code McpApplication} subclass. The role {@code "**"} means any authenticated caller. Features the caller may not
 * use are left out of lists, and invoking one is answered with {@code 403}.
 * <p>
 * An {@code McpApplication} subclass that carries {@code @RolesAllowed} or {@code @DenyAll} declares a
 * <em>protected</em> MCP Server: every request needs an OAuth bearer token issued for it, which Crema validates
 * itself, and the caller and its roles come from the token. It requires MicroProfile Config with these keys, under
 * {@code crema.default-server.} for the default MCP Server or {@code crema.servers.<name>.}:
 * <ul>
 * <li>{@code issuer} (required): the Authorization Server's issuer identifier;</li>
 * <li>{@code jwks-uri}: its JWK set URL, read from its metadata by default;</li>
 * <li>{@code resource}: the MCP Endpoint's public URL, which tokens' audience must contain; derived from each
 * request by default;</li>
 * <li>{@code roles-claim}: the dotted path of the claim with the caller's roles, {@code groups} by default (for
 * example {@code realm_access.roles} for Keycloak or {@code roles} for Entra ID);</li>
 * <li>{@code principal-claim}: the claim with the caller's name, {@code sub} by default;</li>
 * <li>{@code clock-skew-seconds}: the tolerated clock skew, {@code 60} by default.</li>
 * </ul>
 * Its Protected Resource Metadata (RFC 9728) is served at {@code <MCP Endpoint>/.well-known/oauth-protected-resource}.
 * On other MCP Servers, the caller and its roles are the ones the Runtime authenticated.
 *
 * @see McpCaller
 * @see McpServerInfo
 */
public abstract class McpApplication extends Application {

    /**
     * The {@link #getProperties() property} that holds the {@code McpApplication} subclass serving a request.
     */
    public static final String APPLICATION_PROPERTY = "io.github.jungm.crema.application";

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpEndpoint.class, McpEndpointFilter.class, ResourceMetadataEndpoint.class);
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
