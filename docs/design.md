# Crema design

Crema is an MCP server implementation for Jakarta EE. It implements the `org.mcpjava:mcp-server-api:1.0.0` annotations and SPI (https://github.com/mcp-java/java-mcp-annotations) portably on the Jakarta EE 10 Web Profile. It is the source of truth for what Crema does; vocabulary is defined in [CONTEXT.md](../CONTEXT.md), and decisions with trade-offs are recorded in [adr/](adr/).

## 1. Platform

- Coordinates: `io.github.jungm:crema` (library) and `io.github.jungm:crema-it` (integration tests, not deployed). Apache-2.0. Base package `io.github.jungm.crema`.
- Bytecode: Java 17 (`maven.compiler.release=17`). The build and tests run on JDK 21.
- Compile-scope APIs (all `provided`): Jakarta EE 10 Web Profile (`jakarta.platform:jakarta.jakartaee-web-api:10.0.0`), and **optional** `org.eclipse.microprofile.config:microprofile-config-api`. Crema must work when MP Config is absent at runtime: guard every use behind a class-presence check and keep MicroProfile types out of classes that are loaded unconditionally.
- Runtime dependencies (compile scope, shipped transitively in `WEB-INF/lib`): `org.mcpjava:mcp-server-api:1.0.0` and `com.nimbusds:nimbus-jose-jwt` (latest 10.x, not relocated).
- No other runtime dependencies. No vendor-specific code.
- Target Runtimes: Apache TomEE 10.x, Open Liberty, WebSphere Liberty, WildFly (all EE 10+).
- Packaging: a plain JAR in the application's `WEB-INF/lib`, with `META-INF/beans.xml` (`bean-discovery-mode="annotated"`) and a CDI portable extension registered in `META-INF/services/jakarta.enterprise.inject.spi.Extension`. The SPI implementation is registered in `META-INF/services/org.mcpjava.server.spi.McpServerSPI`.

## 2. Protocol

- [protocol-notes.md](protocol-notes.md) digests the spec. Its "Design gaps" G1–G3, G5–G11 and G16 are part of this design. G0 is resolved in §7, G4 by §8 (cancellation stays out), G12 by "JSON-RPC errors use HTTP 200 unless the spec names a status", G13 by following what the conformance suite expects, G14 by §7 (no `scope`), and G15 by §7's Resource Identifier rule. The one exception to G16: application code can't raise `-32021`.
- Implement MCP revision **`2026-07-28` only**, over **Streamable HTTP** only. Read the spec, don't guess: https://modelcontextprotocol.io/specification/2026-07-28 (key pages: `basic/transports/streamable-http`, `basic/versioning`, `server/discover`, `server/tools`, `server/resources`, `server/prompts`, `server/utilities/completion`, `basic/patterns/progress`, `basic/authorization`, `schema`, `changelog`).
- Stateless: no sessions, no `initialize` handshake, no `Mcp-Session-Id` (ignore it if sent; never mint one). `GET` and `DELETE` on the MCP Endpoint return `405`. `Last-Event-ID` is ignored.
- Every request carries `_meta` keys `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientCapabilities` and (SHOULD) `io.modelcontextprotocol/clientInfo`. Any version other than `2026-07-28` produces `400` with `UnsupportedProtocolVersionError` listing `["2026-07-28"]`.
- Every result carries `resultType: "complete"` and `_meta."io.modelcontextprotocol/serverInfo"`.
- Header validation per the transport page: `MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`, `resources/read`, `prompts/get`) `Mcp-Name` are required and must match the body (with Base64 sentinel decoding), else `400` and JSON-RPC error `-32020` HeaderMismatch. An unknown method produces `404` with `-32601`.
- Implemented methods: `server/discover`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `resources/templates/list`, `prompts/list`, `prompts/get`, `completion/complete`. `subscriptions/listen` is not implemented (`-32601`). Capabilities advertise only what is implemented; no `listChanged` or `subscribe` flags.
- Responses are `application/json` unless the Feature Method declares a `Progress` parameter **and** the request carries a `progressToken`, in which case the response is `text/event-stream` carrying `notifications/progress` messages and then the final response. SSE responses set `X-Accel-Buffering: no`.
- List results (`tools/list`, `prompts/list`, `resources/list`, `resources/templates/list`) and `resources/read` carry `ttlMs` and `cacheScope`. `cacheScope` is `"private"` when the MCP Server is protected or any of its Features has role restrictions, otherwise `"public"`. List `ttlMs` comes from `crema.cache.list-ttl-ms` (default `300000`). `resources/read` uses `ttlMs: 0`.
- Lists are sorted by name, and are not paginated (no `nextCursor`).
- Resource not found produces `-32602`.
- Argument errors in `tools/call` (missing required argument, JSON that can't be bound to the parameter type) produce a Tool result with `isError: true` and a message the model can act on, not a JSON-RPC error. An unknown tool name is a JSON-RPC `-32602`.
- A Feature Method that throws produces a Tool result with `isError: true` for Tools. For other Feature kinds it produces a JSON-RPC `-32603` whose message doesn't leak stack traces. `McpException` messages are passed through.

## 3. Declaring MCP Servers

Public API (package `io.github.jungm.crema`):

```java
@ApplicationPath("mcp")
@McpServerInfo(title = "Order Service", instructions = "…")   // optional
public class OrderMcp extends McpApplication {}

@ApplicationPath("mcp/admin")
@McpServerInfo(name = "admin", title = "Back office")
public class AdminMcp extends McpApplication {}
```

- `McpApplication` is an abstract `jakarta.ws.rs.core.Application` whose `getClasses()`/`getSingletons()`/`getProperties()` are `final` and register only Crema's own JAX-RS resources and providers. The application's other providers never apply to MCP traffic.
- Each concrete `McpApplication` subclass declares exactly one MCP Server. Declaring at least one is mandatory for Features to be exposed.
- `@McpServerInfo` attributes: `name` (default `McpServer.DEFAULT`), `title`, `version`, `description`, `instructions`, `websiteUrl`. Empty string means unset. If `version` is unset, fall back to `Implementation-Version` from the WAR's `META-INF/MANIFEST.MF` (via `ServletContext`), else `"0.0.0"`.
- A Crema resource class that is picked up by an application's own scanning `Application` must not serve MCP there. It answers `404` unless the owning `Application` is an `McpApplication`.
- Implementation info sent to clients: `name` = the MCP Server name (`"default"` for the default one), plus title, version, description, websiteUrl and icons.

## 4. Configuration

- Annotations hold code-level facts. **MicroProfile Config is the only configuration source**, and it is optional. Nothing is read from `web.xml`.
- Keys: `crema.default-server.<attr>` for the default MCP Server and `crema.servers.<name>.<attr>` for named ones. `<attr>` ∈ `title`, `version`, `description`, `instructions`, `website-url`, `resource`. Config values override annotation values.
- Global keys: `crema.origin.allowed` (comma-separated origins, `*` disables the check), `crema.cache.list-ttl-ms`.
- If MP Config is absent, annotation values and defaults apply. If an MCP Server is protected (see §7) and MP Config is absent, deployment fails with a clear message.

## 5. Features and programming model

- Feature Methods (`@Tool`, `@Resource`, `@ResourceTemplate`, `@Prompt`) and Completion Methods (`@CompletePrompt`, `@CompleteResourceTemplate`) are discovered on CDI beans by a portable extension (`ProcessManagedBean`/`ProcessAnnotatedType`). Instances are obtained through CDI for each call (`BeanManager`/`Instance`), so scopes, interceptors and `@Inject` work.
- Binding to MCP Servers follows `@McpServer`: the union of method-level and declaring-class-level values. With no `@McpServer`, a Feature binds to the default MCP Server.
- Names default to the Java method name. Argument names come from the annotation's `name`, else the reflective parameter name (requires `-parameters`); if neither is available, deployment fails.
- Injected Parameters: `McpRequest`, `Progress`, `Cancellation` (never reports cancellation), and `CompletionContext` (completion only). All other parameters are Arguments.
- Argument binding: each Argument's JSON value is deserialized with JSON-B into the parameter's generic type. `defaultValue` strings are parsed as JSON literals, falling back to a JSON string for `String`/enum/`char` parameters. `Optional`, `OptionalInt`, `OptionalLong` and `OptionalDouble` parameters are never required. `required=false` with no default binds `null`, or an empty `Optional*` for those types.
- Return conversion (per the API javadoc of each annotation):
  - Tools: `String` → `TextContent`; `ContentBlock` → as-is; `List` of `ContentBlock`/`String` → multiple items; `ToolResponse` → as-is; `void` → empty content; `CompletionStage<T>` → awaited, then converted. Any other type: first try a `ContentEncoder` (CDI beans, most specific type match wins), else JSON-B → one `TextContent`. With `@Tool(structuredContent = true)` the JSON value also goes into `structuredContent`, and `outputSchema` is generated from the return type or `outputSchemaFrom`.
  - Resources and templates: `String` → text contents; `byte[]` → blob (Base64); `ResourceContents`/`List<ResourceContents>`/`ResourceResponse` → as-is; other types → JSON-B text with mime type `application/json`. The `uri` of generated contents is the requested URI.
  - Prompts: `String` → one `USER` text message; `PromptMessage`/`List<PromptMessage>`/`PromptResponse` → as-is. Any other return type **fails deployment**.
  - Completions: `String`/`List<String>`/`CompletionResult`. Any other return type fails deployment. Values are capped at 100 per the spec, with `hasMore` set accordingly.
- JSON Schema (draft 2020-12) for Tool `inputSchema`/`outputSchema` is generated by Crema's own generator. It follows JSON-B mapping rules: `@JsonbProperty` names, `@JsonbTransient`, `@JsonbNillable`, records, getters/public fields, enums (`enum`), collections/arrays (`array` + `items`), `Map<String,V>` (`additionalProperties`), `Optional*`, `java.time` types (`string` + `format`), `BigDecimal`/`BigInteger`, `UUID`, `URI`. Recursive types go through `$defs`/`$ref`. Descriptions come from `@ToolArg(description)`. `required` lists the required Arguments.
- `@MetaField` values go into the definition's `_meta` (with `type` conversion: STRING/INT/BOOLEAN/JSON). `@Icons` providers are CDI beans, or instantiated reflectively when not a bean. `@Tool.Annotations` and `@Resource.Annotations` are emitted only when declared explicitly on the method (not their defaults).
- `ResourceTemplate` matching uses RFC 6570 Level 1 (`{var}`, values without `/`). Each variable binds to the `String` parameter with that name.
- `McpRequest`: `id()` = the JSON-RPC id; `sessionId()` is always empty; `protocolVersion()` comes from `_meta`; `rawClientCapabilities()` comes from `_meta`; `clientInfo()` comes from `_meta`, or is an `ImplementationInfo` with empty name/title/version when absent; `metadata()` is the request's `_meta` minus the `io.modelcontextprotocol/` keys.
- `Progress`: `token()` comes from `_meta.progressToken`. `ProgressNotification.send()` returns a `CompletableFuture<Void>`. Progress notifications are written to the SSE response stream of the current request. Without a token, `send` is a no-op that completes immediately.

## 6. Deployment-time validation

Each of these fails deployment (a CDI `addDeploymentProblem`) with the offending class and method named:
- duplicate Feature names (for each kind) within one MCP Server, or duplicate resource URIs;
- a Feature bound to an MCP Server name that no `McpApplication` declares;
- two `McpApplication`s declaring the same MCP Server name;
- an Argument whose name can't be determined;
- a resource template whose variables don't match its `String` parameters;
- a `@CompletePrompt`/`@CompleteResourceTemplate` referencing an unknown Prompt/Resource Template or Argument, or not having exactly one `String` Argument;
- an unsupported return type for Prompts or Completions;
- an invalid `@MetaField` prefix or name (per the rules in its javadoc).

## 7. Security

- **Origin** (DNS rebinding): an absent `Origin` passes. A present `Origin` passes only if it is a loopback origin (`http`/`https` with host `localhost`, `127.0.0.1` or `[::1]`, any port) or is listed in `crema.origin.allowed` (`*` disables the check). Otherwise respond `403` with a JSON-RPC error that has no `id`. The request's `Host` header is never trusted for this, because in a rebinding attack `Host` and `Origin` both name the attacker's domain.
- **Roles**: `@RolesAllowed`, `@PermitAll` and `@DenyAll` are enforced by Crema on Feature and Completion Methods. Precedence: method > declaring class > `McpApplication` subclass. `"**"` means any authenticated caller. Features the caller may not use are **omitted** from lists. Invoking one yields `403`, with `WWW-Authenticate: Bearer error="insufficient_scope", resource_metadata="…"` when the MCP Server is protected. For open MCP Servers, the caller and roles are the container's (`HttpServletRequest.getUserPrincipal()`/`isUserInRole`). For protected MCP Servers they come from the validated token.
- **Protected MCP Server**: an MCP Server whose `McpApplication` subclass carries `@RolesAllowed` or `@DenyAll`. Crema validates bearer tokens itself, delegating **all** JOSE work to Nimbus JOSE+JWT; see [ADR 0001](adr/0001-crema-validates-bearer-tokens-with-nimbus.md). No hand-written token parsing, signature or claim logic. Specifically:
  - Configuration, per MCP Server (MP Config, so MP Config is required for protected MCP Servers):
    - `issuer` is required.
    - `jwks-uri` is optional. It is otherwise read from the issuer's metadata at `<issuer>/.well-known/openid-configuration`, falling back to RFC 8414 `/.well-known/oauth-authorization-server`, and the metadata's `issuer` must equal the configured one.
    - `resource` (see below).
    - `roles-claim`: a dotted path, default `groups`. It accepts `realm_access.roles` for Keycloak and `roles` for Entra ID.
    - `principal-claim`: default `sub`.
    - `clock-skew-seconds`: default `60`.
  - Deployment fails if a protected MCP Server has no `issuer` or MP Config is absent.
  - Nimbus setup: `JWKSourceBuilder` (with caching, rate limiting and outage tolerance), a `DefaultJWTProcessor` limited to the asymmetric algorithms `RS256/384/512`, `PS256/384/512`, `ES256/384/512` and `EdDSA`, a `typ` of `at+jwt` or `JWT` (or absent), and a `DefaultJWTClaimsVerifier` requiring `iss` = issuer, `aud` ∋ Resource Identifier, and `exp`, and checking `nbf` with the configured skew. No `none`, no HMAC.
  - Responses:
    - No `Authorization: Bearer` header: `401` with `WWW-Authenticate: Bearer resource_metadata="<MCP Endpoint>/.well-known/oauth-protected-resource"`.
    - Any validation failure: `401` with `error="invalid_token"` and `resource_metadata`. Details are logged, never returned.
  - On success, the request's JAX-RS `SecurityContext` is replaced for MCP processing only: principal name from `principal-claim`, roles from `roles-claim`, `isSecure` from the request, and auth scheme `Bearer`.
  - Protected Resource Metadata (RFC 9728) is served unauthenticated at `<MCP Endpoint>/.well-known/oauth-protected-resource`: `resource` is the Resource Identifier, `authorization_servers` is [issuer], and `bearer_methods_supported` is [`header`].
  - Resource Identifier = the `resource` config (the MCP Endpoint's public URL, for use behind reverse proxies), else the MCP Endpoint URL derived from the request. RFC 9728 §3.3 requires it to equal the URL clients use, so it must never be anything else.
  - Tokens are never forwarded anywhere (no passthrough).
- **Caller for Feature Methods**: Injected Parameter `io.github.jungm.crema.McpCaller` (public API: `Principal`, plus `Map<String, Object> claims()`, empty for open MCP Servers) and plain `java.security.Principal`. Both are `null` for anonymous callers. The container (EJB `@RolesAllowed`, Jakarta Security `SecurityContext`, CDI `Principal`) does **not** see token callers.
- OAuth scopes aren't mapped, and challenges carry no `scope` parameter.

## 8. Out of scope for milestone 1

Cancellation signalling (the `Cancellation` parameter never fires), `subscriptions/listen` and change notifications, MRTR/elicitation/sampling/logging, pagination, `x-mcp-header`/`Mcp-Param-*` (the API doesn't express it yet), handshake-era protocol versions, and the stdio transport.

## 9. Testing

- `crema`: JUnit 5 unit tests for everything that doesn't need a Runtime (schema generation, binding, conversion, JSON-RPC handling, validation, header validation).
- `crema-it`: Arquillian deploys test WARs to each Runtime, one Maven profile per Runtime (`tomee`, `openliberty`, `wildfly`, and a non-default `websphere-liberty`), with managed containers that Maven provisions itself. Tests speak MCP over HTTP and cover every method, error path and security case. Tests use a plain `java.net.http.HttpClient` with JSON-P, and validate every result against the 2026-07-28 `schema.json`. The MCP Java SDK doesn't speak 2026-07-28 yet.
- A conformance fixture WAR (the exact `test_*` fixtures listed in protocol-notes.md) runs against the official suite `@modelcontextprotocol/conformance@0.2.0-alpha.11` (`--requirements 2026-07-28`) on at least one Runtime. A checked-in baseline lists only the scenarios Crema doesn't support by design (MRTR, `-32021`), and the run must exit with 0.
- Security integration tests: a protected MCP Server coexists with a Jakarta Security `@OpenIdAuthenticationMechanismDefinition` elsewhere in the same WAR, on every Runtime. Every `401`/`403` path is covered, using tokens minted by a fake Authorization Server in the test JVM (issuer metadata + JWKS, with key rotation).
