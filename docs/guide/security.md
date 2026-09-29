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
- The caller and roles are those of the request's JAX-RS `SecurityContext` (`getUserPrincipal()` and
  `isUserInRole`), that is, whoever your Runtime authenticated, for example via a login configured for the WAR,
  unless Crema authenticates the MCP Server's callers itself. Protected MCP Servers always do; see
  [Authentication](authentication.md).
