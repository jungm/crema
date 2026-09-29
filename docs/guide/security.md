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
  configured for the WAR. An [Authenticator](#protecting-an-mcp-server-without-oauth) replaces it.

## Protecting an MCP Server with OAuth

An `McpApplication` subclass annotated with `@RolesAllowed` or `@DenyAll` declares a **protected** MCP Server. It is
protected either by OAuth, when `issuer` is configured, or by an
[Authenticator](#protecting-an-mcp-server-without-oauth). Deployment fails if it has both or neither.

With OAuth, MCP Clients authenticate with OAuth access tokens: every request needs a bearer token (a signed JWT)
issued for the MCP Server, and Crema validates the token itself. Your Runtime needs no security configuration for this, and your application can
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
- `scopes` (optional, comma-separated) are the OAuth scopes MCP Clients should request. Crema lists them as
  `scopes_supported` in the Protected Resource Metadata and as `scope` in its `401` challenges, and MCP Clients put
  them into their authorization request. This lets a client scope that carries the *Audience* mapper be *Optional*
  instead of *Default*: set `crema.default-server.scopes=crema` for a Keycloak client scope named `crema`. Crema
  doesn't check the token's `scope` claim; the `aud` check stays what protects the MCP Server.
- The JWK set is found through the issuer's `/.well-known/openid-configuration` (or
  `/.well-known/oauth-authorization-server`); set `jwks-uri` to skip discovery. Keys are cached and refreshed when a
  token names an unknown key.
- Accepted algorithms: `RS256/384/512`, `PS256/384/512`, `ES256/384/512`. Unsigned and HMAC-signed tokens are
  rejected. All token processing is done by [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt).

Crema's responses follow the MCP authorization spec: no token gives `401` with `WWW-Authenticate: Bearer
resource_metadata="…"`, an invalid token gives `401` with `error="invalid_token"` (the reason is logged, not sent),
and a missing role gives `403` with `error="insufficient_scope"`. Both `401` challenges carry `scope="…"` when
`scopes` is set; the `403` doesn't, because roles aren't scopes and requesting the same scopes again wouldn't help. A token is invalid, among other reasons, when its
`principal-claim` isn't a non-empty string. The Protected Resource Metadata (RFC 9728) that MCP
Clients use to find the Authorization Server is served at `<MCP Endpoint>/.well-known/oauth-protected-resource`.

Inside a Feature Method, the caller is available as an Injected Parameter:

```java
@Tool(description = "Lists the caller's orders")
public List<Order> myOrders(McpCaller caller) {
    return orders.of(caller.getName(), (String) caller.claims().get("tenant"));
}
```

## Protecting an MCP Server without OAuth

If you don't want to run an Authorization Server, for example because your MCP Clients use a fixed API key, declare
an `McpAuthenticator` bean. It gets the request's header fields and returns the caller with its roles,
`none()` for no credentials, or `rejected()` for invalid ones:

```java
@ApplicationScoped
@McpServer("ops") // the MCP Server it authenticates; the default MCP Server without it
public class OpsApiKeys implements McpAuthenticator {

    @Inject
    @ConfigProperty(name = "ops.api-key")
    String key;

    @Override
    public McpAuthentication authenticate(McpCredentials credentials) {
        return credentials.bearerToken() // or credentials.header("X-Api-Key")
                .map(token -> MessageDigest.isEqual(token.getBytes(UTF_8), key.getBytes(UTF_8))
                        ? McpAuthentication.caller("ops-agent", Set.of("admin"))
                        : McpAuthentication.rejected())
                .orElse(McpAuthentication.none());
    }
}
```

```java
@ApplicationPath("ops")
@McpServerInfo(name = "ops")
@RolesAllowed("admin")
public class OpsMcp extends McpApplication {
}
```

- On a protected MCP Server, `none()` is answered with `401` and `WWW-Authenticate: Bearer`. On an open MCP Server
  the request falls back to the caller your Runtime authenticated, so an Authenticator can also just add callers there.
- `rejected()`, an exception or `null` is answered with `401` and `WWW-Authenticate: Bearer error="invalid_token"`.
  A missing role is a plain `403`.
- If the request repeats a header field the Authenticator reads, or sends a malformed `Bearer` credential, Crema
  rejects it whatever the Authenticator returns.
- No Protected Resource Metadata is served and challenges don't point to one. Configure the key in the MCP Client,
  as a bearer token or a static header. `Authorization: Bearer` is what most MCP Clients support.
- Compare secrets in constant time (`MessageDigest.isEqual`), and only trust identity headers such as `X-User` if a
  proxy in front of the Runtime strips them from client requests.
- Claims you pass to `McpAuthentication.caller(name, roles, claims)` are what `McpCaller.claims()` returns.

See [ADR 0002](../adr/0002-authenticator-spi-for-callers-without-oauth.md) for why this is an SPI rather than
configuration.

## The caller and the Runtime

**The Runtime doesn't see token or Authenticator callers.** Crema authenticates them for MCP requests only, so
`@RolesAllowed` on EJBs, the Jakarta Security `SecurityContext` and the injectable CDI `Principal` still see an
anonymous caller. Pass
`McpCaller` on explicitly where you need it. Tokens are never forwarded anywhere. See
[ADR 0001](../adr/0001-crema-validates-bearer-tokens-with-nimbus.md) for why Crema validates tokens itself instead
of using the Runtime's MicroProfile JWT.
