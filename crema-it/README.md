# crema-it

Integration tests for Crema. Arquillian (JUnit 5, client mode) deploys ShrinkWrap WARs to a managed Runtime that
Maven provisions itself; there is nothing to install by hand.

## Running

JDK 21, one profile per Runtime:

```sh
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21-amzn
mvn -B -pl crema-it -am verify -Ptomee
mvn -B -pl crema-it -am verify -Pwildfly
mvn -B -pl crema-it -am verify -Popenliberty
mvn -B -pl crema-it -am verify -Pwebsphere-liberty
```

Without a profile the integration tests are skipped. Only one profile runs per build.

| Profile             | Runtime                                   | Provisioned by                                              | Arquillian adapter                                              | Ports                                   | Clean run |
|---------------------|-------------------------------------------|-------------------------------------------------------------|-----------------------------------------------------------------|-----------------------------------------|-----------|
| `tomee`             | Apache TomEE 10.2.0, `microprofile` flavor | the adapter resolves `apache-tomee:zip:microprofile` via Maven into `target/tomee` | `org.apache.tomee:arquillian-tomee-remote` 10.2.0              | http 18080, shutdown 18005, no AJP      | ~30 s     |
| `wildfly`           | WildFly 41.0.1.Final, `standalone-microprofile.xml` | `maven-dependency-plugin` unpacks `wildfly-dist` into `target/` | `org.wildfly.arquillian:wildfly-arquillian-container-managed` 5.1.0.Final | port offset 10100 (http 18180, management 20090) | ~30 s     |
| `openliberty`       | Open Liberty 26.0.0.9 (`openliberty-runtime`) | `maven-dependency-plugin` unpacks into `target/liberty`, `src/test/liberty/server.xml` is copied (filtered) | `io.openliberty.arquillian:arquillian-liberty-managed-jakarta` 3.0.0 | http 18280, https 18643                 | ~2.5 min  |
| `websphere-liberty` | WebSphere Liberty 26.0.0.4 (`wlp-jakartaee10`, latest on Maven Central) | same as `openliberty`                                       | same as `openliberty`                                           | http 18380, https 18743                 | ~2.5 min  |

Times are for `mvn clean verify` with the Runtime already in the local Maven repository. Each adapter stops its
server at the end of the run. Liberty uses `webProfile-10.0`, `mpConfig-3.1` and `localConnector-1.0`.

The ports are Maven properties, so builds that run at the same time on one machine can move them apart, for
example by 2000:

```sh
mvn -B -pl crema-it -am verify -Ptomee -Dit.tomee.http.port=20080 -Dit.tomee.stop.port=20005
mvn -B -pl crema-it -am verify -Pwildfly -Dit.wildfly.port.offset=12100 -Dit.wildfly.management.port=22090
mvn -B -pl crema-it -am verify -Popenliberty -Dit.openliberty.http.port=20280 -Dit.openliberty.https.port=20643
mvn -B -pl crema-it -am verify -Pwebsphere-liberty -Dit.websphere-liberty.http.port=20380 \
    -Dit.websphere-liberty.https.port=20743
```

WildFly's ports all follow `it.wildfly.port.offset` (http is 8080 plus the offset); `it.wildfly.management.port` must
be 9990 plus the offset. The Liberty adapter also refuses to start while any JVM on the machine runs a Liberty server
of the same name, so concurrent Liberty builds need distinct `-Dit.liberty.server=<name>` (default `crema-it`).

Each test records its observations as `FINDING` lines on stdout and in `target/findings/<runtime>-<test>.txt`.

A single test class runs with `-Dit.test=TransportIT` (comma-separated for several). `ConformanceIT` needs Node.js
20 or newer (`npx`) and network access the first time `npx` fetches the suite. On Liberty,
`DeploymentFailureIT` takes about 20 s per failing WAR: the adapter only notices the failure when
`appDeployTimeout` expires.

## Protocol, deployment and coexistence tests

The tests speak MCP through `it.mcp.McpClient` (`java.net.http.HttpClient` and JSON-P), which sets the standard
headers and `_meta` and lets a test break any of them. Every JSON-RPC message Crema sends, JSON bodies and SSE
events alike, is validated against the official `2026-07-28` `schema.json` (vendored in
`src/test/resources/mcp/`, source URL and SHA-256 in `WireSchema`) with `com.networknt:json-schema-validator`,
choosing the definition the way the conformance suite's `wire-schema-valid` check does.

| Test | WAR | Covers |
|------|-----|--------|
| `FeaturesIT` | `ProtocolWar`: MCP Servers `default` (`/mcp`) and `admin` (`/mcp/admin`, version from the manifest); Features on `@ApplicationScoped`, `@RequestScoped` and `@Dependent` beans | every method of design §2; records, enums, `BigDecimal`, `Optional*` and defaults as Arguments, records and JavaBeans as return values; every Tool return conversion incl. `ContentEncoder` (most specific wins), `structuredContent`/`outputSchema`, `CompletionStage`, `ToolResponse`, all content block types; resources (text, blob, JSON, multi-part, templates), prompts, completions (context, cap at 100, no Completion Method); `@McpServer` binding; `@Icons`, `@MetaField`, annotations; `McpRequest`; progress over SSE (string and numeric tokens); tool, resource and prompt errors |
| `TransportIT` | same | the check order of protocol-notes G6 (`-32700`, `-32600`, missing headers `-32020`, missing `_meta` fields `-32602` with 400, header mismatch incl. case and Base64 sentinel, `-32022` with `data`, `404`/`-32601` incl. removed methods); `GET`/`DELETE` 405; notifications 202; `Origin` 403 incl. the DNS-rebinding `Host`/`Origin` pair; cursors; `Mcp-Session-Id`; `crema.max-request-bytes` 413; matrix parameters |
| `DeploymentFailureIT` | one WAR per flaw, plus a valid control | duplicate Tool names, a Feature for an undeclared MCP Server, an `McpApplication` overriding `getClasses()`, two `McpApplication`s for one MCP Server, an unsupported Prompt return type, template variables not matching parameters |
| `CoexistenceIT` | `/mcp` next to the application's scanning `Application` at `/api` with its own `@Path("")` root resource, a snake_case `ContextResolver<Jsonb>`, an `ExceptionMapper<Throwable>` and a header-adding `ContainerResponseFilter` | the root resource still serves `GET`/`POST /api/`; the MCP wire format, JSON-B naming, errors, SSE and headers are untouched by the application's providers |
| `ConformanceIT` | the fixtures of protocol-notes "Test tooling" | `npx @modelcontextprotocol/conformance@0.2.0-alpha.11 server --requirements 2026-07-28 --expected-failures src/test/conformance/baseline-2026-07-28.yml` must exit 0; output in `target/conformance/<runtime>/` |

The conformance baseline holds only what Crema doesn't support by design: the 12 MRTR scenarios (two of them end
with a WARNING, which this suite version also counts) and the two `server-stateless` checks for `-32021`. All other
scored scenarios pass on every Runtime.

### Runtime notes

- TomEE hands the providers that the application's scanning `Application` discovers to every `Application` of the
  WAR, including `McpApplication`s. Crema therefore writes MCP responses straight to the servlet response and commits
  them; a servlet filter on the MCP paths then drops the status and headers JAX-RS still sets (Liberty would log
  SRVE8115W for each request otherwise).
- TomEE ignores the application's `ContextResolver<Jsonb>` once another WAR on the same server has used JSON-B
  through JAX-RS; `CoexistenceIT` records that instead of failing. Johnzon (TomEE) writes `BigDecimal` and
  `BigInteger` as JSON strings by default; Crema's own `Jsonb` switches that off.
- Liberty only logs an exception thrown by a `ServletContainerInitializer` and starts the application anyway; Crema
  adds a failing `ServletContextListener` so that the deployment fails.
- `@jakarta.inject.Singleton` isn't bean defining: with `bean-discovery-mode="annotated"` Weld (WildFly, Liberty)
  doesn't discover such a class, OpenWebBeans (TomEE) does.

## Security (protected MCP Servers, ADR 0001)

`ProtectedMcpServerIT` deploys one WAR (built by `SecurityWar`, with Crema, Nimbus and the MCP server API in
`WEB-INF/lib`) containing:

- `/mcp`: a protected MCP Server (`@RolesAllowed("user")` on its `McpApplication`), with Features for role `user`,
  role `admin`, `@PermitAll`, and one that reports its `McpCaller` and `Principal`.
- `/open`: an open MCP Server with an unrestricted Feature and a `@RolesAllowed("admin")` one.
- `/api`: a scanning JAX-RS application without MP-JWT or role constraints; its resource reports the Runtime's caller.
- `/ui`: a servlet with `@ServletSecurity(@HttpConstraint(rolesAllowed = "user"))`, protected by
  `@OpenIdAuthenticationMechanismDefinition` whose `providerURI` comes from MP Config through EL.
- `META-INF/microprofile-config.properties`: `crema.default-server.issuer` and the OIDC provider URI, both the fake
  Authorization Server, and `crema.default-server.resource` = `https://mcp.example.test/security/mcp`, a public URL
  as behind a reverse proxy, which differs from the URL the tests send requests to.

The fake Authorization Server runs in the test JVM on a loopback port: OpenID Connect discovery, a JWK set that the
tests rotate, and an authorization endpoint. Tests mint tokens with Nimbus' signers. The WAR needs nothing
Runtime-specific, and no Runtime security configuration.

### Findings

TomEE 10.2.0, WildFly 41.0.1, Open Liberty 26.0.0.9 and WebSphere Liberty 26.0.0.4 behave the same in every check:

| # | Check | Result on every Runtime |
|---|-------|-------------------------|
| 1 | WAR with a protected MCP Server and the OIDC mechanism deploys | Yes. |
| 2 | `/mcp` without token (`POST` and `GET`) | Crema's `401`, `WWW-Authenticate: Bearer resource_metadata="<resource>/.well-known/oauth-protected-resource"`, empty body. |
| 3 | `/mcp` with an expired, not-yet-valid, wrong-issuer, wrong-audience (the request URL, the open MCP Server's, the API's), foreign-key, `alg: none`, HS256-with-the-public-key or malformed token | Crema's `401` with `error="invalid_token"` and `resource_metadata`, empty body. No Runtime `401`. |
| 4 | `/mcp` with a valid token | `200`; `tools/list` holds only the permitted Tools, with `cacheScope: private`; the Feature Method sees `alice` and the token's claims. |
| 5 | Valid token without the Feature's role | `403`, `WWW-Authenticate: Bearer error="insufficient_scope", resource_metadata="…"`. |
| 6 | Token signed with a key published after the first request | `200`: Nimbus refreshes the JWK set for the unknown `kid`. |
| 7 | `GET <base>/mcp/.well-known/oauth-protected-resource` with a foreign `Origin` | `200` JSON with `resource` = the configured `resource`, `authorization_servers` and `bearer_methods_supported`. `404` for the open MCP Server. |
| 8 | `/open` anonymous; with a bearer token | Runtime caller, anonymous: the `admin` Tool is hidden and answers `403` without `WWW-Authenticate`. A bearer token doesn't change the caller. |
| 9 | `/ui` without login | `302` to the Authorization Server's authorization endpoint. |
| 10 | `/api` without token; with an MCP token | `200` and anonymous in both cases: the Runtime ignores bearer tokens and doesn't see Crema's token callers. |

The application's OIDC mechanism runs on every request, but leaves the unconstrained MCP Endpoints alone, so it
doesn't interfere with Crema's `401`s on any Runtime.
