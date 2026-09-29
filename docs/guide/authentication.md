# Authentication

Crema can authenticate the callers of an MCP Server itself, independently of your Runtime's security configuration:
with OAuth access tokens, with HTTP Basic credentials, or with your own `McpAuthenticator` bean. Which Features a
caller may use is decided by [roles](security.md#roles).

## Protected MCP Servers

An `McpApplication` subclass annotated with `@RolesAllowed` or `@DenyAll` declares a **protected** MCP Server: every
request needs a caller that Crema authenticates. The MicroProfile Config key `<prefix>authenticator` says how, and a
protected MCP Server must set it:

| `authenticator` | Callers authenticate with | Configured by |
|---|---|---|
| `oauth` | OAuth access tokens from an Authorization Server | `issuer`, `resource`, … |
| `basic` | HTTP Basic user name and password | `users`, `users.<name>.password`, `users.<name>.roles` |
| `bean` | whatever your `McpAuthenticator` bean checks | your code |

Your Runtime needs no security configuration for any of them, and your application can keep its own login (for
example `@OpenIdAuthenticationMechanismDefinition`) for other paths. An open MCP Server may also set `basic` or
`bean`: then a request without credentials gets the Runtime's caller.

## OAuth

With `authenticator=oauth`, MCP Clients authenticate with OAuth access tokens: every request needs a bearer token (a
signed JWT) issued for the MCP Server, and Crema validates the token itself.

OAuth is the most involved option, and most of the work is on the Authorization Server, not in Crema. An MCP Client
usually meets your Authorization Server for the first time when it connects, so it has no client ID there yet. The
Authorization Server must let such clients register: through Client ID Metadata Documents (CIMD), where the client ID
is a URL of a document that describes the client, or through Dynamic Client Registration (RFC 7591). Otherwise every
MCP Client has to be registered in advance and configured with its client ID by hand. Check which of these your
Authorization Server and your MCP Clients support before choosing OAuth. Keycloak supports CIMD as an experimental
feature; [Integrating with Model Context Protocol (MCP)](https://www.keycloak.org/securing-apps/mcp-authz-server)
shows how to set it up. If your MCP Clients are agents or scripts
you control, [Basic authentication](#basic-authentication) or [your own Authenticator](#your-own-authenticator) is
simpler.

```java
@ApplicationPath("mcp")
@RolesAllowed("user")
public class OrderMcp extends McpApplication {
}
```

With Keycloak as the Authorization Server, `META-INF/microprofile-config.properties` could be:

```properties
crema.default-server.authenticator=oauth
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
`scopes` is set; the `403` doesn't, because roles aren't scopes and requesting the same scopes again wouldn't help.
A token is invalid, among other reasons, when its `principal-claim` isn't a non-empty string. The Protected Resource
Metadata (RFC 9728) that MCP Clients use to find the Authorization Server is served at
`<MCP Endpoint>/.well-known/oauth-protected-resource`.

## Basic authentication

With `authenticator=basic`, MCP Clients send a user name and password (RFC 7617), and Crema checks them against users
in MicroProfile Config:

```properties
crema.servers.ops.authenticator=basic
crema.servers.ops.users=agent,root
crema.servers.ops.users.agent.password=${OPS_AGENT_PASSWORD}
crema.servers.ops.users.agent.roles=user
crema.servers.ops.users.root.password=${OPS_ROOT_PASSWORD}
crema.servers.ops.users.root.roles=user,admin
```

- Passwords are plaintext. Anyone who can read the configuration can read them, so keep them out of the WAR, for
  example in environment variables or a secrets-backed config source as above.
- User names are case-sensitive and must not contain `:` or whitespace. Passwords may contain anything.
- Missing, malformed or wrong credentials all get `401` with `WWW-Authenticate: Basic realm="ops", charset="UTF-8"`,
  so the response doesn't tell whether a user exists. A missing role gets a plain `403`.
- Basic credentials are only as private as the connection: serve the MCP Endpoint over HTTPS.

## Your own Authenticator

If neither fits, for example because your MCP Clients use API keys kept in a database, declare an `McpAuthenticator`
bean and set `authenticator=bean`. It gets the request's header fields and returns the caller with its roles,
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

```properties
crema.servers.ops.authenticator=bean
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

See [ADR 0002](../adr/0002-authenticator-spi-for-callers-without-oauth.md) and
[ADR 0003](../adr/0003-built-in-basic-authentication-and-an-explicit-authenticator-key.md) for how the Authenticator,
Basic authentication and OAuth relate.

## The caller and the Runtime

Inside a Feature Method, the caller is available as an Injected Parameter. `claims()` holds the token's claims with
OAuth, the claims your Authenticator passed, and is empty with Basic authentication:

```java
@Tool(description = "Lists the caller's orders")
public List<Order> myOrders(McpCaller caller) {
    return orders.of(caller.getName(), (String) caller.claims().get("tenant"));
}
```

**The Runtime doesn't see the callers Crema authenticates.** Crema authenticates them for MCP requests only, so
`@RolesAllowed` on EJBs, the Jakarta Security `SecurityContext` and the injectable CDI `Principal` still see an
anonymous caller. Pass `McpCaller` on explicitly where you need it. Tokens are never forwarded anywhere. See
[ADR 0001](../adr/0001-crema-validates-bearer-tokens-with-nimbus.md) for why Crema validates tokens itself instead
of using the Runtime's MicroProfile JWT.
