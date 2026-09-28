---
status: accepted
---

# Crema validates bearer tokens itself, using Nimbus JOSE+JWT

Supersedes [ADR 0001](0001-runtime-mp-jwt-with-per-server-audience.md). Protected MCP Servers validate bearer tokens in a JAX-RS filter inside `McpApplication`. The filter delegates all JOSE work (JWKS retrieval, caching and rotation, algorithm and signature checks, `typ`, and issuer/audience/expiry verification) to Nimbus JOSE+JWT (`com.nimbusds:nimbus-jose-jwt`), and sets the caller on the JAX-RS `SecurityContext` of MCP requests only. Crema writes no cryptographic or token-parsing code of its own.

## Why ADR 0001 failed

The integration tests in `crema-it` (`SecurityCoexistenceIT`) ran Runtime MP-JWT next to a Jakarta Security `@OpenIdAuthenticationMechanismDefinition` in one WAR:

| | TomEE | WildFly | Open Liberty / WebSphere Liberty |
|---|---|---|---|
| Both in one WAR | works | every request fails: SmallRye JWT registers MP-JWT as a Jakarta Security mechanism, so there are two mechanisms, unless the app supplies an EE 11 `HttpAuthenticationMechanismHandler` | deployment fails with `@LoginConfig`; without it, bearer tokens are ignored |
| Invalid token | Runtime `401` without `WWW-Authenticate` | Runtime `401` without `WWW-Authenticate` | anonymous |

MP-JWT's behaviour is not portable, and on three of four Runtimes it can't coexist with an application's own login.

## Considered Options

- **Runtime MP-JWT, with its limits documented** (ADR 0001): rejected for the reasons above.
- **Hybrid: use a Runtime-authenticated caller if there is one, else Crema's filter**: deferred. It is the path to propagating the caller's identity to the container if that's ever needed.
- **Hand-written JWT validation**: rejected. Security-critical parsing and crypto belong in a battle-tested library.
- **jose4j** instead of Nimbus: also viable (SmallRye JWT uses it). Nimbus was chosen because Spring Security's resource-server support is built on it.

## Consequences

- Protection behaves the same on every Runtime and needs no Runtime security configuration. Crema always controls the `401`/`403` responses, including `resource_metadata` and `error="invalid_token"`.
- The caller is known to Crema (role checks, list filtering) and to Feature Methods through an Injected Parameter, but **not to the container**: `@RolesAllowed` on EJBs, the Jakarta Security `SecurityContext` and CDI's built-in `Principal` don't see it.
- Nimbus is a runtime dependency of Crema. It isn't relocated, so applications can raise its version for security fixes.
- An application's own Jakarta Security mechanism still runs on MCP requests. It must leave `/mcp` unconstrained (which it does unless the app constrains that path), and the integration tests verify this on every Runtime.
