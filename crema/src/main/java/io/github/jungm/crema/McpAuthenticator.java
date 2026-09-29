package io.github.jungm.crema;

/**
 * Authenticates the callers of MCP Servers without OAuth, for example by an API key. An application declares one
 * as a CDI bean, bound to MCP Servers by {@code @McpServer} on its class like Features, or to the default MCP Server
 * without it. A protected MCP Server uses it when its {@code authenticator} key is {@code bean}
 * ({@code crema.default-server.authenticator=bean}); an open one also without the key:
 *
 * <pre>
 * &#64;ApplicationScoped
 * public class ApiKeys implements McpAuthenticator {
 *
 *     &#64;Inject
 *     &#64;ConfigProperty(name = "orders.api-key")
 *     String key;
 *
 *     &#64;Override
 *     public McpAuthentication authenticate(McpCredentials credentials) {
 *         return credentials.bearerToken()
 *                 .map(token -&gt; MessageDigest.isEqual(token.getBytes(UTF_8), key.getBytes(UTF_8))
 *                         ? McpAuthentication.caller("orders-agent", Set.of("user"))
 *                         : McpAuthentication.rejected())
 *                 .orElse(McpAuthentication.none());
 *     }
 * }
 * </pre>
 * <p>
 * Crema calls it for every request to the MCP Endpoint, after the {@code Origin} check and with the CDI request
 * context active. An MCP Server has at most one. How its result is used:
 * <ul>
 * <li>{@link McpAuthentication#caller caller}: the request is processed for that caller, with those roles; the
 * Runtime's view of the caller plays no part;</li>
 * <li>{@link McpAuthentication#none() none}: on a protected MCP Server (one whose {@code McpApplication} subclass
 * carries {@code @RolesAllowed} or {@code @DenyAll}) the answer is {@code 401} with
 * {@code WWW-Authenticate: Bearer}; on any other MCP Server the request is processed for the caller the Runtime
 * authenticated, if any;</li>
 * <li>{@link McpAuthentication#rejected() rejected}: the answer is {@code 401} with
 * {@code WWW-Authenticate: Bearer error="invalid_token"}.</li>
 * </ul>
 * An exception or a {@code null} result counts as rejected and is logged. An MCP Server with an authenticator
 * serves no Protected Resource Metadata. Comparing secrets is up to the authenticator; use
 * {@link java.security.MessageDigest#isEqual} or another constant-time comparison.
 * <p>
 * The Runtime doesn't see the callers an authenticator returns, just as it doesn't see bearer token callers.
 *
 * @see McpApplication
 */
@FunctionalInterface
public interface McpAuthenticator {

    /**
     * Decides who sends a request.
     *
     * @return who the caller is, or that the request carries no or invalid credentials; never {@code null}
     */
    McpAuthentication authenticate(McpCredentials credentials);
}
