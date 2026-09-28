# Programming model

Feature Methods are annotated with `@Tool`, `@Resource`, `@ResourceTemplate` or `@Prompt`; Completion Methods with
`@CompletePrompt` or `@CompleteResourceTemplate`. The annotations and types come from `org.mcpjava.server.*`; their
javadoc is the reference. Names default to the Java method name.

## Arguments and binding

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

## Injected Parameters

These parameter types are supplied by Crema, not by the client:

| Type | What it is |
|------|------------|
| `org.mcpjava.server.McpRequest` | The JSON-RPC id, protocol version, client info and capabilities, and the request's `_meta` |
| `org.mcpjava.server.progress.Progress` | Sends progress notifications (see below) |
| `org.mcpjava.server.Cancellation` | Accepted, but never reports a cancellation (see [Limitations](limitations.md)) |
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

## Return types

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

## Structured content

With `@Tool(structuredContent = true)` the JSON value of the return type also goes into the result's
`structuredContent`, and the Tool declares an `outputSchema` generated from the return type (or from
`outputSchemaFrom`). Any JSON value is allowed, including arrays such as `List<Order>`.

```java
@Tool(description = "Places an order", structuredContent = true)
public Order placeOrder(String customer, List<Item> items) { ... }
```

## Multiple MCP Servers

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

## Deployment-time validation

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
