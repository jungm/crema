# Security

## Origin check

To stop DNS rebinding attacks, a request that carries an `Origin` header is refused with `403` unless that origin is a
loopback origin (`localhost`, `127.0.0.0/8`, `[::1]`, any port) or is listed in `crema.origin.allowed`. Requests
without `Origin` (typical for non-browser MCP Clients) pass. The check doesn't enable CORS: Crema answers no
preflight requests.

## Roles

Crema enforces `@RolesAllowed`, `@PermitAll` and `@DenyAll` on Feature and Completion Methods. The annotation on the
method wins over the one on its class, which wins over the one on the `McpApplication` subclass. The role `"**"`
means any authenticated caller.

- Features the caller may not use are left out of `tools/list` and the other lists. That's a convenience, not
  confidentiality: calling one answers `403`, not "unknown".
- On an open MCP Server the caller and roles are those of the request's JAX-RS `SecurityContext`
  (`getUserPrincipal()` and `isUserInRole`), that is, whoever your Runtime authenticated, for example via a login
  configured for the WAR.

## Protecting an MCP Server with OAuth

MCP Clients authenticate with OAuth access tokens. An `McpApplication` subclass annotated with `@RolesAllowed` or
`@DenyAll` declares a **protected** MCP Server: every request needs a bearer token (a signed JWT) issued for it, and
Crema validates the token itself. Your Runtime needs no security configuration for this, and your application can
keep its own login (for example `@OpenIdAuthenticationMechanismDefinition`) for other paths.

```java
@ApplicationPath("mcp")
@RolesAllowed("user")
public class OrderMcp extends McpApplication {
}
```

With Keycloak as the Authorization Server, `META-INF/microprofile-config.properties` could be:

```properties
crema.default-server.issuer=https://keycloak.example.com/realms/shop
crema.default-server.resource=https://shop.example.com/shop/mcp
crema.default-server.roles-claim=realm_access.roles
```

- `resource` is the MCP Endpoint's URL exactly as clients use it. It is the MCP Server's Resource Identifier, and
  Crema never derives it from the request, because the `Host` header is under the caller's control.
- The Authorization Server must put the `resource` URL into the access token's `aud` claim. In Keycloak, add an
  *Audience* mapper to the client scope your MCP Clients get, with *Included Custom Audience* set to the `resource`
  URL. Tokens for other audiences, such as those for your REST API or for another MCP Server, are rejected.
- The JWK set is found through the issuer's `/.well-known/openid-configuration` (or
  `/.well-known/oauth-authorization-server`); set `jwks-uri` to skip discovery. Keys are cached and refreshed when a
  token names an unknown key.
- Accepted algorithms: `RS256/384/512`, `PS256/384/512`, `ES256/384/512`. Unsigned and HMAC-signed tokens are
  rejected. All token processing is done by [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt).

Crema's responses follow the MCP authorization spec: no token gives `401` with `WWW-Authenticate: Bearer
resource_metadata="…"`, an invalid token gives `401` with `error="invalid_token"` (the reason is logged, not sent),
and a missing role gives `403` with `error="insufficient_scope"`. A token is invalid, among other reasons, when its
`principal-claim` isn't a non-empty string. The Protected Resource Metadata (RFC 9728) that MCP
Clients use to find the Authorization Server is served at `<MCP Endpoint>/.well-known/oauth-protected-resource`.

Inside a Feature Method, the caller is available as an Injected Parameter:

```java
@Tool(description = "Lists the caller's orders")
public List<Order> myOrders(McpCaller caller) {
    return orders.of(caller.getName(), (String) caller.claims().get("tenant"));
}
```

**The Runtime doesn't see token callers.** Crema authenticates them for MCP requests only, so `@RolesAllowed` on EJBs,
the Jakarta Security `SecurityContext` and the injectable CDI `Principal` still see an anonymous caller. Pass
`McpCaller` on explicitly where you need it. Tokens are never forwarded anywhere. See
[ADR 0001](../adr/0001-crema-validates-bearer-tokens-with-nimbus.md) for why Crema validates tokens itself instead
of using the Runtime's MicroProfile JWT.
