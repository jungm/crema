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
 * Crema serves every request to this path itself, ahead of the application's JAX-RS filters, resources and
 * providers, which therefore never apply to MCP traffic. Subclasses must not declare any methods, in particular
 * not override {@link #getClasses()}, {@link #getSingletons()} or {@link #getProperties()}; deployment fails if they
 * do. Features are exposed only through an {@code McpApplication}.
 * <p>
 * Crema enforces {@code @RolesAllowed}, {@code @PermitAll} and {@code @DenyAll} on Feature and Completion Methods:
 * the annotation on the method wins over the one on its declaring class, which wins over the one on the
 * {@code McpApplication} subclass. The role {@code "**"} means any authenticated caller. Features the caller may not
 * use are left out of lists, and invoking one is answered with {@code 403}. Leaving them out is a convenience for
 * clients, not confidentiality: a client that knows a Feature's name can still tell that it exists from the
 * {@code 403}.
 * <p>
 * An {@code McpApplication} subclass that carries {@code @RolesAllowed} or {@code @DenyAll} declares a
 * <em>protected</em> MCP Server: every request needs a caller that Crema authenticates. The MicroProfile Config key
 * {@code authenticator}, under {@code crema.default-server.} for the default MCP Server or
 * {@code crema.servers.<name>.}, says how, and a protected MCP Server must set it:
 * <ul>
 * <li>{@code oauth}: OAuth bearer tokens, see below;</li>
 * <li>{@code basic}: HTTP Basic authentication against the users named by {@code users}, each with a plaintext
 * {@code users.<name>.password} and comma-separated {@code users.<name>.roles};</li>
 * <li>{@code bean}: the {@link McpAuthenticator} bean bound to the MCP Server.</li>
 * </ul>
 * An open MCP Server may set {@code basic} or {@code bean} too; without the key it uses its
 * {@link McpAuthenticator} if it has one.
 * <p>
 * With OAuth, every request needs an OAuth bearer token issued for the MCP Server, which Crema validates itself, and
 * the caller and its roles come from the token. It is configured with these keys:
 * <ul>
 * <li>{@code issuer} (required): the Authorization Server's issuer identifier;</li>
 * <li>{@code jwks-uri}: its JWK set URL, read from its metadata by default;</li>
 * <li>{@code resource} (required): the MCP Endpoint's public URL, which tokens' audience must contain. It is never
 * derived from the request;</li>
 * <li>{@code roles-claim}: the dotted path of the claim with the caller's roles, {@code groups} by default (for
 * example {@code realm_access.roles} for Keycloak or {@code roles} for Entra ID);</li>
 * <li>{@code principal-claim}: the claim with the caller's name, {@code sub} by default;</li>
 * <li>{@code clock-skew-seconds}: the tolerated clock skew, {@code 60} by default.</li>
 * </ul>
 * The {@code Authorization} header must hold exactly one {@code Bearer} token in the syntax of RFC 6750. Tokens
 * must be JWTs signed with RSA or ECDSA. Their {@code typ} may be {@code at+jwt}, {@code JWT} or absent, because
 * many Authorization Servers don't issue {@code at+jwt} access tokens; that an ID token or other JWT of the same
 * Authorization Server isn't accepted as an access token relies on {@code resource}: a token is only accepted if its
 * audience contains this MCP Server's Resource Identifier.
 * <p>
 * Its Protected Resource Metadata (RFC 9728) is served at {@code <MCP Endpoint>/.well-known/oauth-protected-resource}
 * and advertised to clients as {@code <resource>/.well-known/oauth-protected-resource}.
 * <p>
 * On an open MCP Server, the caller and its roles are the ones the Runtime authenticated, unless Basic
 * authentication or an {@link McpAuthenticator} authenticates the request.
 * <p>
 * The Runtime doesn't know about the callers Crema authenticates: {@code @RolesAllowed} on EJBs, the Jakarta Security
 * {@code SecurityContext} and CDI's built-in {@code Principal} bean don't see them. Feature Methods receive the
 * caller as an Injected Parameter, {@link McpCaller} or {@link java.security.Principal}, and pass it on explicitly
 * where it's needed.
 *
 * @see McpAuthenticator
 * @see McpCaller
 * @see McpServerInfo
 */
public abstract class McpApplication extends Application {

    /**
     * Crema's own resource and request filter; the filter serves every request to this application.
     * <p>
     * Not {@code final}, like {@link #getSingletons()} and {@link #getProperties()}, because Liberty proxies
     * {@code Application} subclasses as normal-scoped CDI beans, which requires overridable methods. Subclasses
     * that override it fail deployment.
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(McpEndpoint.class, McpEndpointFilter.class);
    }

    /**
     * None. Not {@code final} for the reason given at {@link #getClasses()}; subclasses that override it fail
     * deployment.
     */
    @Override
    public Set<Object> getSingletons() {
        return Set.of();
    }

    /**
     * The property by which Crema's filter tells which MCP Server a request is for. Not {@code final} for the
     * reason given at {@link #getClasses()}; subclasses that override it fail deployment.
     */
    @Override
    public Map<String, Object> getProperties() {
        return Map.of(McpEndpointFilter.APPLICATION_PROPERTY, getClass());
    }
}
