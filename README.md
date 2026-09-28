<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/crema-logo-dark.svg">
    <img src="docs/images/crema-logo.svg" alt="Crema" width="420">
  </picture>
</h1>

<p align="center">An MCP server implementation for Jakarta EE.</p>

## What it is

The [Model Context Protocol](https://modelcontextprotocol.io) (MCP) lets AI applications (MCP Clients such as
chat assistants and coding agents) discover and call functionality that your application offers: **Tools** the model
can invoke, **Resources** it can read, and **Prompts** a user can pick. Crema lets a Jakarta EE web application offer
these from ordinary CDI beans: you annotate methods, and Crema serves them over HTTP next to the rest of your
application. It implements the [`org.mcpjava:mcp-server-api`](https://github.com/mcp-java/java-mcp-annotations)
annotations and MCP protocol revision
[`2026-07-28`](https://modelcontextprotocol.io/specification/2026-07-28) over Streamable HTTP. It is a plain library
in your WAR that uses only standard Jakarta EE APIs. Its one concession to a vendor is two JSON-B provider properties,
set through the standard `JsonbConfig`, that make Apache Johnzon (TomEE) write `BigDecimal` and `BigInteger` as JSON
numbers; other providers ignore them.

Requirements:

- Java 17 or newer
- A Jakarta EE 10 Web Profile (or newer) Runtime. Crema is tested on Apache TomEE 10, WildFly, Open Liberty and
  WebSphere Liberty.
- MicroProfile Config is optional, except for OAuth-protected MCP Servers.

## Quickstart

### 1. Add the dependency

Crema isn't released yet; build it yourself (see [Building and testing](#building-and-testing)) to get
`0.1.0-SNAPSHOT` into your local Maven repository.

```xml
<dependency>
    <groupId>io.github.jungm</groupId>
    <artifactId>crema</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use the default `compile` scope: Crema, `mcp-server-api` and Nimbus JOSE+JWT must end up in `WEB-INF/lib` of your
WAR. Compile with `-parameters` (`<maven.compiler.parameters>true</maven.compiler.parameters>`) so that Crema can use
Java parameter names as Argument names.

### 2. Declare an MCP Server

An MCP Server is declared by a subclass of `McpApplication`, a JAX-RS `Application`. Its `@ApplicationPath` is the
MCP Endpoint that clients connect to.

```java
import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("mcp")
@McpServerInfo(title = "Order Service", instructions = "Use these tools to look up and place orders.")
public class OrderMcp extends McpApplication {
}
```

The subclass must stay empty: Crema fails the deployment if it declares any methods. It coexists with your own JAX-RS
`Application`s, and your providers (exception mappers, JSON-B configuration, filters, message body readers and
writers) don't touch MCP traffic: Crema answers MCP requests inside its own `@PreMatching` request filter. The one
exception is TomEE, which applies the providers your scanning `Application` discovers to every `Application` of the
WAR, so an application `@PreMatching` request filter that TomEE orders before Crema's runs for MCP requests too.

### 3. Add Features to a CDI bean

Any CDI bean with a bean-defining annotation (`@ApplicationScoped`, `@RequestScoped`, `@Dependent`, ...) can carry
Feature Methods. Crema gets the instance from CDI for each call, so `@Inject`, interceptors and transactions work as
usual.

```java
import java.math.BigDecimal;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

@ApplicationScoped
public class OrderFeatures {

    public record Item(String sku, int quantity) {}
    public record Order(String id, String customer, List<Item> items, BigDecimal total) {}

    @Inject
    OrderService orders;

    @Tool(description = "Places an order for a customer")
    public Order placeOrder(@ToolArg(description = "Customer number") String customer,
                            @ToolArg(description = "What to order") List<Item> items) {
        return orders.place(customer, items);
    }

    @Resource(uri = "orders://recent", description = "The 20 most recent orders", mimeType = "application/json")
    public List<Order> recentOrders() {
        return orders.recent(20);
    }

    @ResourceTemplate(uriTemplate = "orders://{id}", name = "order", description = "One order")
    public Order order(String id) {
        return orders.find(id);
    }

    @Prompt(description = "Summarizes a customer's order history")
    public String orderSummary(@PromptArg(description = "Customer number") String customer) {
        return "Summarize the order history of customer " + customer + ". Use the order tools and resources.";
    }
}
```

Crema generates the JSON Schema for `placeOrder`'s Arguments from the Java types, binds the client's JSON to them
(the `Item` records through JSON-B), and returns the `Order` as JSON text.

### 4. Try it

Deploy the WAR. The MCP Endpoint is `http://<host>:<port>/<context-root>/mcp`. Point an MCP Client that supports
revision `2026-07-28` over Streamable HTTP at that URL, or talk to it with `curl`. MCP `2026-07-28` is stateless:
there is no handshake, and every request carries the protocol version and client capabilities in its `_meta`, plus a
few headers that repeat what's in the body:

```sh
curl -s http://localhost:8080/shop/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {"_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}}}}'
```

To call a Tool, also send `Mcp-Name` with the Tool's name (for `resources/read`, the resource URI; for
`prompts/get`, the Prompt's name):

```sh
curl -s http://localhost:8080/shop/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: placeOrder' \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
        "name": "placeOrder",
        "arguments": {"customer": "C-42", "items": [{"sku": "coffee-beans", "quantity": 2}]},
        "_meta": {
          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
          "io.modelcontextprotocol/clientCapabilities": {}}}}'
```

`server/discover` describes the MCP Server and its capabilities. MCP `2026-07-28` has no server-to-client stream, so
`GET` and `DELETE` on the MCP Endpoint answer `405`. That comes after the `Origin` check and, on a protected MCP
Server, after authentication, so an unauthenticated `GET` there gets `401`.

## Programming model

Feature Methods are annotated with `@Tool`, `@Resource`, `@ResourceTemplate` or `@Prompt`; Completion Methods with
`@CompletePrompt` or `@CompleteResourceTemplate`. The annotations and types come from `org.mcpjava.server.*`; their
javadoc is the reference. Names default to the Java method name.

### Arguments and binding

Every parameter that isn't an Injected Parameter is an Argument supplied by the client.

- The Argument name is the annotation's `name` (`@ToolArg`, `@PromptArg`, `@ResourceTemplateArg`, `@CompleteArg`),
  else the Java parameter name.
- Crema binds scalars (primitives and their wrappers, `String`, `BigDecimal`, `BigInteger`, enums), `Optional*`,
  arrays, the standard collections and `Map<String, V>` itself, so that an error names the offending element.
  Everything else, such as records, JavaBeans, `UUID` and `java.time` types, is deserialized with JSON-B. The Tool's
  `inputSchema` follows the JSON-B mapping rules (`@JsonbProperty`, `@JsonbTransient`, ...).
- Scalar binding is lenient toward models, so it accepts slightly more than the `inputSchema` advertises: `"5"`
  binds to an `int`, `5` to a `String`, and `"TRUE"` to a `boolean`.
- `defaultValue` is parsed as a JSON literal; for `String`, `char` and enum parameters a plain string works too
  (`defaultValue = "NORMAL"`).
- `required = false` with a `defaultValue` binds the default; without one it binds `null`, or an empty `Optional*`.
  `Optional`, `OptionalInt`, `OptionalLong` and `OptionalDouble` parameters are never required. A primitive
  parameter can't be `null`, so with `required = false` it needs a `defaultValue`.
- A missing or unbindable Tool Argument produces a Tool result with `isError: true` and a message the model can act
  on.
- Resource Template variables (`{id}`) bind to the `String` parameter of the same name. Values are percent-decoded,
  and values that are empty, contain `/` or `\`, or are `.` or `..` don't match, so a value is safe to use as a single
  path segment.

### Injected Parameters

These parameter types are supplied by Crema, not by the client:

| Type | What it is |
|------|------------|
| `org.mcpjava.server.McpRequest` | The JSON-RPC id, protocol version, client info and capabilities, and the request's `_meta` |
| `org.mcpjava.server.progress.Progress` | Sends progress notifications (see below) |
| `org.mcpjava.server.Cancellation` | Accepted, but never reports a cancellation (see [Limitations](#limitations)) |
| `org.mcpjava.server.completion.CompletionContext` | Completion Methods only: the other Arguments already entered |
| `io.github.jungm.crema.McpCaller` | The caller, with the token's claims on a protected MCP Server; `null` if anonymous |
| `java.security.Principal` | The caller; `null` if anonymous |

A Tool reports progress when the client asked for it by sending a `progressToken`. The response is then streamed as
Server-Sent Events:

```java
@Tool(description = "Re-indexes the catalogue")
public String reindex(Progress progress) {
    List<Product> products = catalogue.all();
    for (int i = 0; i < products.size(); i++) {
        index(products.get(i));
        if (progress.token().isPresent()) {
            progress.notificationBuilder().setProgress(i + 1).setTotal(products.size()).build().send();
        }
    }
    return "Indexed " + products.size() + " products";
}
```

### Return types

| Feature | Supported return types |
|---------|-----------------------|
| Tool | `String` (text), `ContentBlock` (`TextContent`, `ImageContent`, `AudioContent`, `ResourceLink`, `EmbeddedResource`), `List` of those or of `String`, `ToolResponse`, `void`, `CompletionStage<T>` of any of these; any other type is rendered by a matching `ContentEncoder` CDI bean if there is one, else as JSON text via JSON-B |
| Resource, Resource Template | `String` (text), `byte[]` (blob), `ResourceContents`, `List<ResourceContents>`, `ResourceResponse`; any other type as JSON text via JSON-B, with the declared `mimeType` or `application/json` |
| Prompt | `String` (one user message), `PromptMessage`, `List<PromptMessage>`, `PromptResponse` |
| Completion | `String`, `List<String>`, `CompletionResult` (at most 100 values are sent, with `hasMore` set) |

Keys of `_meta` entries, whether from `@MetaField` or from the builders of the API's value types, must follow the
MCP key format, and prefixes reserved for MCP are rejected: `putMetadata` throws an `IllegalArgumentException`.

An exception thrown by a Tool becomes a Tool result with `isError: true`; for other Features it becomes a JSON-RPC
error. Only the message of an `org.mcpjava.server.McpException` reaches the client; other exceptions are reported
without details.

### Structured content

With `@Tool(structuredContent = true)` the JSON value of the return type also goes into the result's
`structuredContent`, and the Tool declares an `outputSchema` generated from the return type (or from
`outputSchemaFrom`). Any JSON value is allowed, including arrays such as `List<Order>`.

```java
@Tool(description = "Places an order", structuredContent = true)
public Order placeOrder(String customer, List<Item> items) { ... }
```

### Multiple MCP Servers

One WAR can offer several MCP Servers, for example a public one and an administrative one. Give each
`McpApplication` subclass its own path and a name, and bind Features with `@McpServer` on the method or class:

```java
@ApplicationPath("mcp/admin")
@McpServerInfo(name = "admin", title = "Back office")
public class AdminMcp extends McpApplication {
}

@ApplicationScoped
@McpServer("admin")
public class AdminTools {

    @Tool(description = "Purges the caches")
    public String purge() { ... }
}
```

Features without `@McpServer` belong to the default MCP Server (an `McpApplication` without `@McpServerInfo`, or
with no `name`). A Feature can be bound to several MCP Servers by repeating `@McpServer`, for example
`@McpServer(McpServer.DEFAULT) @McpServer("admin")`. `@McpServerInfo` also takes `version`, `description` and
`websiteUrl`; without a `version`, the `Implementation-Version` of the WAR's `META-INF/MANIFEST.MF` is used.

### Deployment-time validation

Crema checks the model when the application starts and fails the deployment, naming the class and method, for:

- Feature and Completion Methods:
  - a private Feature or Completion Method, or one with more than one of `@Tool`, `@Resource`,
    `@ResourceTemplate`, `@Prompt`, `@CompletePrompt` and `@CompleteResourceTemplate`
  - an Argument whose name can't be determined (compile with `-parameters`), or two Arguments with the same name
  - a `defaultValue` that can't be bound to its parameter, or a primitive parameter with `required = false` and no
    `defaultValue`
  - a `CompletionContext` parameter outside a Completion Method
  - a Resource with Arguments or an empty `uri`
  - Resource Template variables that aren't Level 1 (`{name}`) or don't match its `String` parameters
  - `@Tool(structuredContent = true)` on a `void` method, or on one returning `ToolResponse` without
    `outputSchemaFrom`
  - a Prompt or Completion Method with an unsupported return type, or a Completion Method without exactly one
    `String` Argument
  - an invalid `@MetaField`: a malformed prefix or name, a prefix reserved for MCP (such as
    `io.modelcontextprotocol/` or `tools.mcp.com/`), a value that doesn't match its `type`, or two `@MetaField`s
    with the same key
  - `@Resource.Annotations` with a `priority` outside 0.0 to 1.0, or a `lastModified` that isn't an ISO 8601
    date-time with offset (`2025-01-12T15:00:58Z`)
  - an `@Icons` provider that can't be instantiated or whose `getIcons` throws
- MCP Servers:
  - two Features of the same kind with the same name, or two Resources with the same URI, in one MCP Server
  - a Feature bound to an MCP Server name that no `McpApplication` declares, or two `McpApplication`s declaring the
    same name
  - a Completion Method referring to an unknown Prompt, Resource Template or Argument
  - an `McpApplication` subclass that declares methods
  - a Feature Method, class or `McpApplication` carrying more than one of `@DenyAll`, `@PermitAll` and
    `@RolesAllowed`
- Configuration:
  - `crema.cache.list-ttl-ms`, `crema.max-request-bytes` or `clock-skew-seconds` that isn't a number in range
  - a protected MCP Server without MicroProfile Config, without `issuer`, or without a well-formed `resource` (the
    absolute `http(s)` URL of the MCP Endpoint, without user info, query or fragment)
  - an `issuer` or `jwks-uri` that isn't an `https` URL (`http` only for `localhost`)
- Packaging: Crema shared between several web applications, or an `McpApplication` in a WAR where CDI is not active

Two Resource Templates of the same shape in one MCP Server, such as `db:///{table}` and `db:///{name}`, only log a
warning: only the one whose name sorts first is ever used.

## Configuration

Annotations hold code-level facts; deployment-specific values come from MicroProfile Config (for example
`WEB-INF/classes/META-INF/microprofile-config.properties`). Config values override annotation values. Without
MicroProfile Config, the annotation values and defaults apply. Nothing is read from `web.xml`.

Per-server keys use the prefix `crema.default-server.` for the default MCP Server and `crema.servers.<name>.` for a
named one (`crema.servers.admin.title`, ...).

| Key | Default | Meaning |
|-----|---------|---------|
| `<prefix>title`, `version`, `description`, `instructions`, `website-url` | from `@McpServerInfo` | Server information sent to clients |
| `<prefix>issuer` | none; required when protected | Issuer identifier of the Authorization Server |
| `<prefix>resource` | none; required when protected | Public URL of the MCP Endpoint; access tokens' `aud` must contain it |
| `<prefix>jwks-uri` | from the issuer's metadata | JWK set URL of the Authorization Server |
| `<prefix>roles-claim` | `groups` | Dotted path of the claim that holds the caller's roles |
| `<prefix>principal-claim` | `sub` | Dotted path of the claim that holds the caller's name; tokens without it as a non-empty string are rejected |
| `<prefix>clock-skew-seconds` | `60` | Tolerated clock skew for `exp` and `nbf` |
| `crema.origin.allowed` | empty (loopback only) | Comma-separated `Origin`s that may call the MCP Endpoints; `*` disables the check |
| `crema.cache.list-ttl-ms` | `300000` | `ttlMs` caching hint on `server/discover` and list results |
| `crema.max-request-bytes` | `4194304` | Largest accepted request body; larger ones get `413` |

## Security

### Origin check

To stop DNS rebinding attacks, a request that carries an `Origin` header is refused with `403` unless that origin is a
loopback origin (`localhost`, `127.0.0.1`, `[::1]`, any port) or is listed in `crema.origin.allowed`. Requests
without `Origin` (typical for non-browser MCP Clients) pass. The check doesn't enable CORS: Crema answers no
preflight requests.

### Roles

Crema enforces `@RolesAllowed`, `@PermitAll` and `@DenyAll` on Feature and Completion Methods. The annotation on the
method wins over the one on its class, which wins over the one on the `McpApplication` subclass. The role `"**"`
means any authenticated caller.

- Features the caller may not use are left out of `tools/list` and the other lists. That's a convenience, not
  confidentiality: calling one answers `403`, not "unknown".
- On an open MCP Server the caller and roles are those of the request's JAX-RS `SecurityContext`
  (`getUserPrincipal()` and `isUserInRole`), that is, whoever your Runtime authenticated, for example via a login
  configured for the WAR.

### Protecting an MCP Server with OAuth

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
[ADR 0001](docs/adr/0001-crema-validates-bearer-tokens-with-nimbus.md) for why Crema validates tokens itself instead
of using the Runtime's MicroProfile JWT.

## Limitations

Not supported (yet):

- Protocol: only revision `2026-07-28` over Streamable HTTP. No older handshake-based revisions, no stdio transport.
- Cancellation: `Cancellation` never fires, and a client closing the stream doesn't stop the Feature Method.
- `subscriptions/listen` and change notifications (`listChanged`, resource subscriptions).
- Multi-round-trip requests (MRTR), elicitation, sampling and logging.
- Pagination of lists; everything is returned in one page.
- `x-mcp-header` / `Mcp-Param-*` headers.
- CORS: browser-based MCP Clients on a foreign origin need an allowlisted origin, and Crema answers no preflights.
- OAuth: one issuer per MCP Server; JWT access tokens only (no token introspection); scopes aren't mapped to roles,
  and challenges carry no `scope`.
- Packaging: Crema must be in each WAR's `WEB-INF/lib`. It can't be shared through an EAR's `lib/` directory or a
  Runtime-level shared library, and each WAR needs CDI enabled.

## Building and testing

The build needs JDK 21 (the bytecode targets Java 17) and Maven.

```sh
mvn -B install            # build, run the unit tests and install 0.1.0-SNAPSHOT locally
mvn -B -pl crema verify   # unit tests only
```

Integration tests in `crema-it` deploy test WARs to a real Runtime, which Maven downloads and starts itself. They
speak MCP over HTTP and validate every response against the official `2026-07-28` JSON Schema. Pick one profile per
build:

```sh
mvn -B -pl crema-it -am verify -Ptomee
mvn -B -pl crema-it -am verify -Pwildfly
mvn -B -pl crema-it -am verify -Popenliberty
mvn -B -pl crema-it -am verify -Pwebsphere-liberty
```

`ConformanceIT` runs the official MCP conformance suite (`@modelcontextprotocol/conformance`) against a fixture WAR;
it needs Node.js 20 or newer. Run a single test class with `-Dit.test=ConformanceIT`. See
[crema-it/README.md](crema-it/README.md) for ports, Runtime versions and what each test covers.

Further reading: [docs/design.md](docs/design.md) (what Crema does, in detail), [CONTEXT.md](CONTEXT.md) (vocabulary)
and [docs/protocol-notes.md](docs/protocol-notes.md) (the protocol, from the server's side).

## License

[Apache License 2.0](LICENSE)
