# An Authenticator SPI for callers without OAuth

Some applications want to protect an MCP Server without running an Authorization Server, for example with an API key that an MCP Client sends as a static header. Crema offers a public SPI for this: a CDI bean implementing `McpAuthenticator` that maps a request's header fields to a Caller with roles, to "no credentials" or to "rejected". A protected MCP Server has either an `issuer` (OAuth, ADR 0001) or an Authenticator, never both.

## Considered Options

- **The application's own Jakarta Security `HttpAuthenticationMechanism`** on an open MCP Server: already works for Feature-level `@RolesAllowed`, because open MCP Servers use the Runtime's caller. It isn't enough on its own. Jakarta EE 10 allows only one mechanism per application, so it collides with the application's own login (the reason ADR 0001 rejected a Crema mechanism), and it can't make the whole MCP Server require a caller, because `@RolesAllowed` on the `McpApplication` means OAuth.
- **Configuration only** (API keys, or trusted header names such as `X-User`, with roles in MicroProfile Config): rejected. Trusting an identity header is only safe behind a proxy that strips it, which Crema can't check. Keys in configuration need hashing, constant-time comparison and rotation, and that is security policy the application should own. It can still be added later as an `McpAuthenticator` that Crema provides.
- **OAuth as the default Authenticator**, with the SPI able to replace it: deferred. OAuth needs more than the SPI expresses: challenges with `resource_metadata` and `scope`, `insufficient_scope` on `403`, the Protected Resource Metadata endpoint, and closing the JWKS refresh. Exposing that would make the public SPI much larger. The two stay separate behind the SPI. Internally they may share one abstraction.
- **Handing the Authenticator the `HttpServletRequest`**: rejected. It would leak servlet types into the API and let the Authenticator consume the body before dispatch. `McpCredentials` exposes the header fields only.

## Consequences

- An MCP Server can require a caller without OAuth and without Runtime security configuration, alongside the application's own login.
- The Authenticator is a CDI bean invoked per request, so it can inject repositories or configuration.
- A repeated header field or a malformed `Bearer` credential that the Authenticator reads makes Crema reject the request, whatever the Authenticator returns, so two credentials can't be played off against each other.
- MCP Clients get `401` with `WWW-Authenticate: Bearer`, without `resource_metadata`, and no Protected Resource Metadata is served, so nothing points them to an Authorization Server. The credential is configured in the client, for example as a static header.
- As with tokens, the container doesn't see the Authenticator's caller.
