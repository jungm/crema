package io.github.jungm.crema;

import java.security.Principal;
import java.util.Map;

/**
 * The caller of a Feature Method or Completion Method, supplied as an Injected Parameter:
 *
 * <pre>
 * &#64;Tool
 * public String myOrders(McpCaller caller) {
 *     return orders.of(caller.getName(), (String) caller.claims().get("tenant"));
 * }
 * </pre>
 * <p>
 * A parameter of this type, or of type {@link Principal}, is {@code null} when the caller is anonymous.
 * <ul>
 * <li>On a protected MCP Server (its {@code McpApplication} subclass carries {@code @RolesAllowed} or
 * {@code @DenyAll}) the caller is the subject of the validated bearer token. {@link #getName()} is the value of the
 * configured principal claim ({@code sub} by default) and {@link #claims()} holds all claims of the token.</li>
 * <li>On any other MCP Server the caller is the one the Runtime authenticated, and {@link #claims()} is empty.</li>
 * </ul>
 * Bearer tokens with a {@code typ} of {@code at+jwt}, {@code JWT} or none are accepted, for compatibility with
 * Authorization Servers; the audience check against the configured {@code resource} is what binds a token to this
 * MCP Server.
 * <p>
 * Features the caller may not use are left out of lists as a convenience, not for confidentiality: invoking one is
 * answered with {@code 403}, so their existence isn't secret.
 * <p>
 * The Runtime doesn't know about bearer token callers: {@code @RolesAllowed} on EJBs, the Jakarta Security
 * {@code SecurityContext} and CDI's built-in {@code Principal} bean don't see them. Pass the caller on explicitly
 * where it's needed.
 */
public interface McpCaller extends Principal {

    /**
     * The claims of the validated access token, as parsed by Nimbus JOSE+JWT: JSON strings, numbers and booleans
     * as {@code String}, {@code Number} and {@code Boolean}, arrays as {@code List}, objects as {@code Map}, and
     * the time claims {@code exp}, {@code nbf} and {@code iat} as {@code java.util.Date}. Empty unless the MCP
     * Server is protected. The map is unmodifiable.
     */
    Map<String, Object> claims();
}
