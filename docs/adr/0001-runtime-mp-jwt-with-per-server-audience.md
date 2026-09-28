# Runtime MP-JWT validates tokens; Crema enforces a per-MCP-Server audience

Protected MCP Servers rely on the Runtime's MicroProfile JWT (`@LoginConfig(authMethod = "MP-JWT")` on the `McpApplication` subclass) for signature, expiry and issuer validation, and Crema additionally requires the MCP Server's Resource Identifier in the token's `aud`. Crema itself implements only the MCP-specific parts: the `401` challenge with `resource_metadata`, the Protected Resource Metadata document, role checks and hiding of Features.

## Considered Options

- **Crema ships its own Jakarta Security `HttpAuthenticationMechanism`** that verifies JWTs with JDK crypto. Rejected because Jakarta EE 10 allows only one mechanism per application, so it collides with an app that already uses `@OpenIdAuthenticationMechanismDefinition` for its web UI. EE 11's qualified mechanisms would lift this, but not every target Runtime supports EE 11 yet.
- **Rely on `mp.jwt.verify.audiences` alone.** Rejected because MP-JWT has one verifier per WAR that accepts any listed audience. With an API and MCP in the same WAR, API tokens would be accepted at the MCP Endpoint and vice versa, which is the token passthrough the MCP authorization spec forbids. It also can't tell two MCP Servers in one WAR apart.

## Consequences

- MCP tokens and the application's other tokens must come from the same issuer, because `mp.jwt.verify.issuer` is single-valued. A different Authorization Server for MCP requires a separate WAR.
- Roles come from MP-JWT's `groups` claim, not from OAuth scopes.
- If `mp.jwt.verify.audiences` is set and doesn't contain a statically configured Resource Identifier, deployment fails, because MP-JWT would otherwise reject every MCP token.
