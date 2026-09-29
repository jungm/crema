# Crema design

Crema is an MCP server implementation for Jakarta EE. It implements the `org.mcpjava:mcp-server-api:1.0.0` annotations and SPI (https://github.com/mcp-java/java-mcp-annotations) portably on the Jakarta EE 10 Web Profile. It is the source of truth for what Crema does; vocabulary is defined in [CONTEXT.md](../CONTEXT.md), and decisions with trade-offs are recorded in [adr/](adr/).

## 1. Platform

- Coordinates: `io.github.jungm.crema:crema` (library) and `io.github.jungm.crema:crema-it` (integration tests, not deployed). Apache-2.0. Base package `io.github.jungm.crema`.
- Bytecode: Java 17 (`maven.compiler.release=17`). The build and tests run on JDK 21.
- Compile-scope APIs (all `provided`): Jakarta EE 10 Web Profile (`jakarta.platform:jakarta.jakartaee-web-api:10.0.0`), and **optional** `org.eclipse.microprofile.config:microprofile-config-api`. Crema must work when MP Config is absent at runtime: guard every use behind a class-presence check and keep MicroProfile types out of classes that are loaded unconditionally.
- Runtime dependencies (compile scope, shipped transitively in `WEB-INF/lib`): `org.mcpjava:mcp-server-api:1.0.0` and `com.nimbusds:nimbus-jose-jwt` (latest 10.x, not relocated).
- No other runtime dependencies. No vendor-specific code, except provider properties set through the standard `JsonbConfig` that other providers ignore (Johnzon writes `BigDecimal`/`BigInteger` as strings unless told otherwise).
- Target Runtimes: Apache TomEE 10.x, Open Liberty, WildFly, Payara (all EE 10+).
- Packaging: a plain JAR in the application's `WEB-INF/lib`, with `META-INF/beans.xml` (`bean-discovery-mode="annotated"`) and a CDI portable extension registered in `META-INF/services/jakarta.enterprise.inject.spi.Extension`. The SPI implementation is registered in `META-INF/services/org.mcpjava.server.spi.McpServerSPI`.

## 2. Protocol

- [protocol-notes.md](protocol-notes.md) digests the spec. Its "Design gaps" G1–G3, G5–G11 and G16 are part of this design. G0 is resolved in §7, G4 by §8 (cancellation stays out), G12 by "JSON-RPC errors use HTTP 200 unless the spec names a status", G13 by following what the conformance suite expects, G14 by §7 (`scope` on `401` only), and G15 by §7's Resource Identifier rule. The one exception to G16: application code can't raise `-32021`.
- Implement MCP revision **`2026-07-28` only**, over **Streamable HTTP** only. Read the spec, don't guess: https://modelcontextprotocol.io/specification/2026-07-28 (key pages: `basic/transports/streamable-http`, `basic/versioning`, `server/discover`, `server/tools`, `server/resources`, `server/prompts`, `server/utilities/completion`, `basic/patterns/progress`, `basic/authorization`, `schema`, `changelog`).
- Stateless: no sessions, no `initialize` handshake, no `Mcp-Session-Id` (ignore it if sent; never mint one). `GET` and `DELETE` on the MCP Endpoint return `405`. That comes after the Origin check and, for protected MCP Servers, authentication, so an unauthenticated `GET` gets `401`. `Last-Event-ID` is ignored.
- Every request carries `_meta` keys `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientCapabilities` and (SHOULD) `io.modelcontextprotocol/clientInfo`. Any version other than `2026-07-28` produces `400` with `UnsupportedProtocolVersionError` listing `["2026-07-28"]`.
- Every result carries `resultType: "complete"` and `_meta."io.modelcontextprotocol/serverInfo"`.
- Header validation per the transport page: `MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`, `resources/read`, `prompts/get`) `Mcp-Name` are required and must match the body (with Base64 sentinel decoding), else `400` and JSON-RPC error `-32020` HeaderMismatch. An unknown method produces `404` with `-32601`.
- Implemented methods: `server/discover`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `resources/templates/list`, `prompts/list`, `prompts/get`, `completion/complete`. `subscriptions/listen` is not implemented (`-32601`). Capabilities advertise only what is implemented; no `listChanged` or `subscribe` flags.
- Responses are `application/json` unless the Feature Method declares a `Progress` parameter **and** the request carries a `progressToken`, in which case the response is `text/event-stream` carrying `notifications/progress` messages and then the final response. SSE responses set `X-Accel-Buffering: no`.
- List results (`tools/list`, `prompts/list`, `resources/list`, `resources/templates/list`) and `resources/read` carry `ttlMs` and `cacheScope`. `cacheScope` is decided per response: it is `"private"` when the MCP Server is protected, any of its Features has role restrictions, or the request has an authenticated caller, and `"public"` otherwise. List `ttlMs` comes from `crema.cache.list-ttl-ms` (default `300000`). `resources/read` uses `ttlMs: 0`.
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

- `McpApplication` is an abstract `jakarta.ws.rs.core.Application` whose `getClasses()`/`getSingletons()`/`getProperties()` register only Crema's own JAX-RS resources and providers. They aren't `final`, because Liberty proxies `Application` subclasses as normal-scoped CDI beans. A subclass that overrides any of them, or declares any methods at all (on TomEE that switches off provider scanning for the whole WAR), fails deployment. The application's other providers never apply to MCP traffic.
- Each concrete `McpApplication` subclass declares exactly one MCP Server. Declaring at least one is mandatory for Features to be exposed.
- `@McpServerInfo` attributes: `name` (default `McpServer.DEFAULT`), `title`, `version`, `description`, `instructions`, `websiteUrl`. Empty string means unset. If `version` is unset, fall back to `Implementation-Version` from the WAR's `META-INF/MANIFEST.MF` (via `ServletContext`), else `"0.0.0"`.
- A Crema resource class that is picked up by an application's own scanning `Application` must not serve MCP there. It answers `404` unless the owning `Application` is an `McpApplication`.
- Crema handles MCP requests, and the Protected Resource Metadata, completely inside its own `@PreMatching` request filter, which runs with the highest priority. It writes the response directly to the servlet response, commits it and aborts the JAX-RS request. TomEE applies an application's scanned providers to every `Application` in the WAR, so this is the only portable way to keep them off MCP traffic: the application's post-matching request filters, resource methods, exception mappers and message body readers/writers never run, and its response filters can't change the committed response. The one exception is an application `@PreMatching` filter that TomEE applies to the `McpApplication` and orders before Crema's.
- Implementation info sent to clients: `name` = the MCP Server name (`"default"` for the default one), plus title, version, description, websiteUrl and icons.

## 4. Configuration

- Annotations hold code-level facts. **MicroProfile Config is the only configuration source**, and it is optional. Nothing is read from `web.xml`.
- Keys: `crema.default-server.<attr>` for the default MCP Server and `crema.servers.<name>.<attr>` for named ones. `<attr>` ∈ `title`, `version`, `description`, `instructions`, `website-url`, `resource`. Config values override annotation values.
- Global keys: `crema.origin.allowed` (comma-separated origins, `*` disables the check; it doesn't enable CORS, and Crema answers no preflights), `crema.cache.list-ttl-ms`, `crema.max-request-bytes` (default `4194304`; a larger body, by `Content-Length` or while reading, gets `413` with a JSON-RPC `-32600` error that has no `id`).
- If MP Config is absent, annotation values and defaults apply. If an MCP Server is protected (see §7) and MP Config is absent, deployment fails with a clear message.

## 5. Features and programming model

- Feature Methods (`@Tool`, `@Resource`, `@ResourceTemplate`, `@Prompt`) and Completion Methods (`@CompletePrompt`, `@CompleteResourceTemplate`) are discovered on CDI beans by a portable extension (`ProcessManagedBean`/`ProcessAnnotatedType`). Instances are obtained through CDI for each call (`BeanManager`/`Instance`), so scopes, interceptors and `@Inject` work.
- Binding to MCP Servers follows `@McpServer`: the union of method-level and declaring-class-level values. With no `@McpServer`, a Feature binds to the default MCP Server.
- Names default to the Java method name. Argument names come from the annotation's `name`, else the reflective parameter name (requires `-parameters`); if neither is available, deployment fails.
- Injected Parameters: `McpRequest`, `Progress`, `Cancellation` (never reports cancellation), and `CompletionContext` (completion only). All other parameters are Arguments.
- Argument binding: Crema binds scalars, enums, `Optional*`, arrays, standard collections and `Map<String, V>` itself, and application classes through JSON-B. Scalar binding is deliberately lenient toward models (`"5"` binds to `int`, `5` to `String`, `"TRUE"` to `boolean`), so it accepts slightly more than the advertised `inputSchema`. `defaultValue` strings are parsed as JSON literals, falling back to a JSON string for `String`/enum/`char` parameters. `Optional`, `OptionalInt`, `OptionalLong` and `OptionalDouble` parameters are never required. `required=false` with no default binds `null`, or an empty `Optional*` for those types. A primitive parameter can't be absent, so it needs a `defaultValue` (deployment fails otherwise).
- Return conversion (per the API javadoc of each annotation):
  - Tools: `String` → `TextContent`; `ContentBlock` → as-is; `List` of `ContentBlock`/`String` → multiple items; `ToolResponse` → as-is; `void` → empty content; `CompletionStage<T>` → awaited, then converted. Any other type: first try a `ContentEncoder` (CDI beans, most specific type match wins), else JSON-B → one `TextContent`. With `@Tool(structuredContent = true)` the JSON value also goes into `structuredContent`, and `outputSchema` is generated from the return type or `outputSchemaFrom`. In 2026-07-28 both may be any JSON value or schema (SEP-2106), so non-object types such as `List<T>` are allowed.
  - Resources and templates: `String` → text contents; `byte[]` → blob (Base64); `ResourceContents`/`List<ResourceContents>`/`ResourceResponse` → as-is; other types → JSON-B text with the declared `mimeType`, else `application/json`. The `uri` of generated contents is the requested URI.
  - Prompts: `String` → one `USER` text message; `PromptMessage`/`List<PromptMessage>`/`PromptResponse` → as-is. Any other return type **fails deployment**.
  - Completions: `String`/`List<String>`/`CompletionResult`. Any other return type fails deployment. Values are capped at 100 per the spec, with `hasMore` set accordingly.
- JSON Schema (draft 2020-12) for Tool `inputSchema`/`outputSchema` is generated by Crema's own generator. It follows JSON-B mapping rules: `@JsonbProperty` names, `@JsonbTransient`, `@JsonbNillable`, records, getters/public fields, enums (`enum`), collections/arrays (`array` + `items`), `Map<String,V>` (`additionalProperties`), `Optional*`, `java.time` types (`string` + `format`), `BigDecimal`/`BigInteger`, `UUID`, `URI`. Recursive types go through `$defs`/`$ref`. Descriptions come from `@ToolArg(description)`. `required` lists the required Arguments.
- `@MetaField` values go into the definition's `_meta` (with `type` conversion: STRING/INT/BOOLEAN/JSON). `@Icons` providers are CDI beans, or instantiated reflectively when not a bean. `@Tool.Annotations` and `@Resource.Annotations` values are emitted only where they differ from the spec defaults.
- `ResourceTemplate` matching uses RFC 6570 Level 1 (`{var}`). Each variable binds to the `String` parameter with that name. Values are percent-decoded, and a match is rejected (→ not found) if a decoded value is empty, contains `/` or `\`, starts with a Windows drive such as `C:`, or is `.` or `..`. Applications can therefore use a value as a single path segment safely. Two templates that can match the same URI are reported at deployment only if they have identical shape (a warning).
- `McpRequest`: `id()` = the JSON-RPC id; `sessionId()` is always empty; `protocolVersion()` comes from `_meta`; `rawClientCapabilities()` comes from `_meta`; `clientInfo()` comes from `_meta`, or is an `ImplementationInfo` with empty name/title/version when absent; `metadata()` is the request's `_meta` minus the `io.modelcontextprotocol/` keys and `progressToken`.
- `Progress`: `token()` comes from `_meta.progressToken`. `ProgressNotification.send()` returns a `CompletableFuture<Void>`. Progress notifications are written to the SSE response stream of the current request. As the API javadoc specifies, `notificationBuilder()`/`trackerBuilder()` throw `IllegalStateException` when the request has no progress token; application code checks `token().isPresent()` first.

## 6. Deployment-time validation

Each of these, among other checks the README lists, fails deployment with the offending class and method named. Crema reports it as a CDI deployment problem, or from its `ServletContainerInitializer` and a `ServletContextListener` (Liberty only logs exceptions from initializers but refuses to start an application whose listener fails):
- duplicate Feature names (for each kind) within one MCP Server, or duplicate resource URIs;
- a Feature bound to an MCP Server name that no `McpApplication` declares;
- two `McpApplication`s declaring the same MCP Server name;
- an Argument whose name can't be determined;
- a resource template whose variables don't match its `String` parameters;
- a `@CompletePrompt`/`@CompleteResourceTemplate` referencing an unknown Prompt/Resource Template or Argument, or not having exactly one `String` Argument;
- an unsupported return type for Prompts or Completions;
- an invalid `@MetaField` prefix or name (per the rules in its javadoc), or two `@MetaField`s with the same key on one method;
- Crema's classes being shared by more than one web application (Crema must be in each WAR's `WEB-INF/lib`), or an `McpApplication` present while CDI is inactive for the WAR.

## 7. Security

- **Origin** (DNS rebinding): an absent `Origin` passes. A present `Origin` passes only if it is a loopback origin (`http`/`https` with host `localhost`, an address in `127.0.0.0/8` or `[::1]`, any port) or is listed in `crema.origin.allowed` (`*` disables the check). Otherwise respond `403` with a JSON-RPC error that has no `id`. The request's `Host` header is never trusted for this, because in a rebinding attack `Host` and `Origin` both name the attacker's domain.
- **Roles**: `@RolesAllowed`, `@PermitAll` and `@DenyAll` are enforced by Crema on Feature and Completion Methods. Precedence: method > declaring class > `McpApplication` subclass. `"**"` means any authenticated caller. Features the caller may not use are **omitted** from lists. That is a convenience, not confidentiality: calling a hidden Feature answers `403` rather than "unknown", because MCP's step-up authorization relies on `insufficient_scope`. Invoking one yields `403`, with `WWW-Authenticate: Bearer error="insufficient_scope", resource_metadata="…"` when the MCP Server uses OAuth. For open MCP Servers, the caller and roles are the Runtime's (the JAX-RS `SecurityContext`). For protected MCP Servers they come from the validated token or the Authenticator.
- **Protected MCP Server**: an MCP Server whose `McpApplication` subclass carries `@RolesAllowed` or `@DenyAll`. Every request needs a caller that its Authentication Mechanism authenticates.
- **Authentication Mechanism**: chosen per MCP Server by the MP Config key `authenticator`: `oauth`, `basic` or `bean`. See [ADR 0003](adr/0003-built-in-basic-authentication-and-an-explicit-authenticator-key.md).
  - A protected MCP Server must set it, so protected MCP Servers need MP Config. Deployment fails without it, with an unknown value, with `oauth` on an open MCP Server, with `bean` and no Authenticator bound, and with `oauth` or `basic` and an Authenticator bound.
  - An open MCP Server may set `basic` or `bean`. Without the key it uses its Authenticator if one is bound, else the Runtime's caller.
  - Keys of an unselected mechanism (`issuer`, `users`) log a warning at deployment.
  - Internally each mechanism implements one interface that authenticates a request and shapes its `401`, `403` and Protected Resource Metadata answers.
- **OAuth**: Crema validates bearer tokens itself, delegating **all** JOSE work to Nimbus JOSE+JWT; see [ADR 0001](adr/0001-crema-validates-bearer-tokens-with-nimbus.md). No hand-written token parsing, signature or claim logic. Specifically:
  - Configuration, per MCP Server (MP Config, so MP Config is required for OAuth):
    - `issuer` is required.
    - `jwks-uri` is optional. It is otherwise read from the issuer's metadata at `<issuer>/.well-known/openid-configuration`, falling back to RFC 8414 `/.well-known/oauth-authorization-server`, and the metadata's `issuer` must equal the configured one.
    - `resource` is required (see below).
    - `roles-claim`: a dotted path, default `groups`. It accepts `realm_access.roles` for Keycloak and `roles` for Entra ID.
    - `principal-claim`: a dotted path, default `sub`. A token without it as a non-empty string is rejected.
    - `clock-skew-seconds`: default `60`.
    - `scopes`: optional, comma-separated RFC 6749 scope tokens. Deployment fails on one that isn't a valid scope token.
  - Deployment fails if an MCP Server using OAuth has no `issuer` or `resource`.
  - Nimbus setup: `JWKSourceBuilder` (with caching, rate limiting and outage tolerance), a `DefaultJWTProcessor` limited to the asymmetric algorithms `RS256/384/512`, and `PS256/384/512`, `ES256/384/512`, a `typ` of `at+jwt` or `JWT` (or absent), and a `DefaultJWTClaimsVerifier` requiring `iss` = issuer, `aud` ∋ Resource Identifier, and `exp`, and checking `nbf` with the configured skew. No `none`, no HMAC.
  - Responses:
    - No `Authorization: Bearer` header: `401` with `WWW-Authenticate: Bearer resource_metadata="<MCP Endpoint>/.well-known/oauth-protected-resource"`.
    - Any validation failure: `401` with `error="invalid_token"` and `resource_metadata`. Details are logged, never returned.
  - On success, the request is processed for the token's caller: principal name from `principal-claim`, roles from `roles-claim`. A `Bearer` credential that isn't exactly one RFC 6750 `b64token` (whitespace, other characters, a repeated `Authorization` header) is `invalid_token`.
  - Both `401` challenges carry `scope="<scopes, space-separated>"` when `scopes` is set.
  - Protected Resource Metadata (RFC 9728) is served unauthenticated at `<MCP Endpoint>/.well-known/oauth-protected-resource`: `resource` is the Resource Identifier, `authorization_servers` is [issuer], `bearer_methods_supported` is [`header`], and `scopes_supported` is the `scopes` when set.
  - Resource Identifier = the `resource` config: the MCP Endpoint's public URL. It is never derived from the request, because the `Host` header is attacker-controlled and would let a token issued for another resource of the same Authorization Server pass the audience check. RFC 9728 §3.3 requires it to equal the URL clients use.
  - Tokens are never forwarded anywhere (no passthrough).
- **Authenticator**: a CDI managed bean implementing the public `io.github.jungm.crema.McpAuthenticator`, for callers without OAuth (API keys, a trusted proxy, …); see [ADR 0002](adr/0002-authenticator-spi-for-callers-without-oauth.md). It binds to MCP Servers by `@McpServer` on its class, like Features (default MCP Server without it). Deployment fails if it names an undeclared MCP Server or if an MCP Server has two. It works on open and protected MCP Servers.
  - Input: `McpCredentials`, the MCP Server's name and header fields only (`header(name)`, `bearerToken()`). The body isn't available. A header field the request repeats, or a `Bearer` credential that isn't exactly one RFC 6750 `b64token`, reads as empty and makes Crema reject the request whatever the Authenticator returns.
  - Output: `McpAuthentication`: `caller(name, roles[, claims])`, `none()` or `rejected()`. It's invoked once per request after the Origin check, with the request context active, through CDI (so dependent instances are destroyed afterwards).
  - Responses: `caller` processes the request for that caller (the Runtime's caller plays no part). `none` is `401` with `WWW-Authenticate: Bearer` on a protected MCP Server, and the Runtime's caller on an open one. `rejected`, an exception or `null` is `401` with `Bearer error="invalid_token"`. Exceptions are logged as rate-limited warnings. A missing role is `403` without a challenge. No Protected Resource Metadata is served, and no challenge carries `resource_metadata` or `scope`, since there is no Authorization Server to point to.
  - Crema doesn't provide an Authenticator that trusts identity headers: whether a proxy strips them is the application's knowledge.
- **Basic** (RFC 7617): users in MP Config, `users` (comma-separated names, without `:` or whitespace), and per user `users.<name>.password` (plaintext, required) and `users.<name>.roles` (comma-separated, optional).
  - `Authorization: Basic` is Base64 (strict) of UTF-8 `name:password`, split at the first `:`. User names are case-sensitive.
  - Passwords are compared as SHA-256 digests with `MessageDigest.isEqual`. An unknown user is compared against a dummy digest, so it takes as long as a wrong password.
  - No credentials, another scheme, malformed credentials, a repeated `Authorization` header, an unknown user and a wrong password all get the same `401` with `WWW-Authenticate: Basic realm="<MCP Server name>", charset="UTF-8"`. The reason is logged at `FINE`. A missing role is `403` without a challenge. On an open MCP Server, no credentials (or another scheme) falls back to the Runtime's caller.
- **Caller for Feature Methods**: Injected Parameter `io.github.jungm.crema.McpCaller` (public API: `Principal`, plus `Map<String, Object> claims()`: the token's claims, the Authenticator's claims, or empty for the Runtime's caller) and plain `java.security.Principal`. Both are `null` for anonymous callers. The container (EJB `@RolesAllowed`, Jakarta Security `SecurityContext`, CDI `Principal`) does **not** see token or Authenticator callers.
- OAuth scopes aren't mapped to roles, and the token's `scope` claim isn't checked. `scopes` only tells MCP Clients what to request. `403` challenges carry no `scope` parameter: roles aren't scopes, so no scope would let a client step up.

## 8. Out of scope for milestone 1

Cancellation signalling (the `Cancellation` parameter never fires), `subscriptions/listen` and change notifications, MRTR/elicitation/sampling/logging, pagination, `x-mcp-header`/`Mcp-Param-*` (the API doesn't express it yet), handshake-era protocol versions, and the stdio transport.

## 9. Testing

- `crema`: JUnit 5 unit tests for everything that doesn't need a Runtime (schema generation, binding, conversion, JSON-RPC handling, validation, header validation).
- `crema-it`: Arquillian deploys test WARs to each Runtime, one Maven profile per Runtime (`tomee`, `openliberty`, `wildfly` and `payara`), with managed containers that Maven provisions itself. Tests speak MCP over HTTP and cover every method, error path and security case. Tests use a plain `java.net.http.HttpClient`, and validate every MCP result, including those of the security tests, against the 2026-07-28 `schema.json`. The MCP Java SDK doesn't speak 2026-07-28 yet.
- A conformance fixture WAR (the exact `test_*` fixtures listed in protocol-notes.md) runs against the official suite `@modelcontextprotocol/conformance@0.2.0-alpha.11` (`--requirements 2026-07-28`) on at least one Runtime. A checked-in baseline lists only the scenarios Crema doesn't support by design (MRTR, `-32021`), and the run must exit with 0.
- Security integration tests: a protected MCP Server coexists with a Jakarta Security `@OpenIdAuthenticationMechanismDefinition` elsewhere in the same WAR, on every Runtime. Every `401`/`403` path is covered, using tokens minted by a fake Authorization Server in the test JVM (issuer metadata + JWKS, with key rotation).
