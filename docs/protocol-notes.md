# MCP 2026-07-28 protocol notes (server side)

A digest of MCP revision `2026-07-28`, written for implementing Crema, taken from primary sources only:

- Spec pages: `https://modelcontextprotocol.io/specification/2026-07-28/...`. The sources are the `.mdx` files under `docs/specification/2026-07-28/` in github.com/modelcontextprotocol/modelcontextprotocol.
- Schema: `schema/2026-07-28/schema.ts` (the source of truth) and the generated `schema.json` in the same repo. The example JSON files are in `schema/2026-07-28/examples/`.

Where the spec and this digest disagree, the spec wins. "MUST", "SHOULD" and similar words are quoted from the spec.

---

## 0. Design gaps

These are points where `docs/design.md` is silent, wrong or looser than the spec. They are ordered by severity.

### G0. The design's `Origin` check doesn't stop DNS rebinding, which is what it exists for
The design passes a request when `Origin` "matches … the request's own scheme://host[:port]". In a DNS-rebinding attack, the browser sends `Host: evil.example.com` **and** `Origin: http://evil.example.com`. The two match, so the design lets the request through.
> "Servers **MUST** validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks. If the `Origin` header is present and invalid, servers **MUST** respond with HTTP 403 Forbidden." (basic/transports/streamable-http#security--endpoint)

The conformance scenario `dns-rebinding-protection` (required for 2026-07-28) sends exactly that pair. Both headers are set to `evil.example.com` (conformance `src/scenarios/server/dns-rebinding.ts:104-120`). It expects a `4xx`, and it expects a `2xx` when both headers are the URL's own localhost host.

Fix: derive the "own origin" from a trusted source, never from the `Host` header alone. Candidates are a configured public base URL, `crema.origin.allowed`, or loopback names when the server is bound locally. Also reject a `Host` header that names neither a configured host nor loopback (`localhost`, `127.0.0.1`, `[::1]`). Whether to make that allowlist mandatory or to default it to loopback plus the configured origins is a design decision. The current rule is a no-op in exactly the attack scenario.

### G1. Missing required `_meta` fields must return `-32602` and HTTP `400`
The design only covers an unsupported version. It doesn't cover an absent `protocolVersion` or `clientCapabilities`, or absent `params`/`_meta`. In the schema, `RequestParams._meta` is **required** on every client request, including `server/discover` and the list methods.
> "A request missing any required field is malformed; the server **MUST** reject it with JSON-RPC error code `-32602` (Invalid params). On HTTP, the response status **MUST** be `400 Bad Request`." (basic/index#_meta)

The required fields are `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities`. `clientInfo` is optional.

### G2. `UnsupportedProtocolVersionError` has code `-32022`, and its `data` carries `requested` as well
The design says "`UnsupportedProtocolVersionError` listing `["2026-07-28"]`" but gives neither the code nor `requested`. In the schema, both `data.supported: string[]` and `data.requested: string` are **required** (schema.ts `UnsupportedProtocolVersionError`):
```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version",
 "data":{"supported":["2026-07-28"],"requested":"2025-11-25"}}}
```
The code was renumbered from `-32004` during drafting (changelog, minor change 12). Don't copy `-32004` from older SDKs.

### G3. `server/discover` is a `CacheableResult`
The design lists `ttlMs`/`cacheScope` only for the lists and `resources/read`.
> "Servers MUST include caching hints on results with `resultType: "complete"` returned by the following operations: `server/discover`, `tools/list`, `prompts/list`, `resources/list`, `resources/templates/list`, `resources/read`" (server/utilities/caching)

`DiscoverResult extends CacheableResult`, so both `ttlMs` and `cacheScope` are required. `ttlMs` "**MUST** … be `>= 0`", so `crema.cache.list-ttl-ms` must be validated.

### G4. Closing the SSE stream is a cancellation that the server MUST honour
The design says the `Cancellation` parameter "never reports cancellation" and lists cancellation signalling as out of scope. The spec makes the server side mandatory:
> "Closing the SSE response stream **MUST** be treated by the server as cancellation of that request. … The server **SHOULD** stop work on the cancelled request as soon as practical and **MUST NOT** send any further messages for it." (basic/transports/streamable-http#cancellation; also basic/patterns/cancellation: "The server **MUST** treat a client disconnect as cancellation of that request.")

Minimum compliance: detect the disconnect (write failure, or `AsyncListener.onError`/`onComplete` on the async servlet context). After that, `Progress.send` becomes a completed no-op and nothing more is written. Ideally `Cancellation` fires too, which is cheap once the disconnect is detected.

### G5. JSON-RPC notifications POSTed to the endpoint must get `202` with no body
The design only covers requests.
> "If the body is a JSON-RPC _notification_: If the server accepts it, the server **MUST** return HTTP status code `202 Accepted` with no body. If the server cannot accept it, it **MUST** return an HTTP error status code (e.g., `400 Bad Request`)." (streamable-http#sending-messages)

> "The body of the HTTP POST **MUST** be a single JSON-RPC _request_ or _notification_. The client **MUST NOT** send JSON-RPC _responses_."

This revision defines no client-to-server notifications over HTTP; `notifications/cancelled` is stdio-only. The rule still applies to anything with no `id`, so return `202` and ignore it. For a JSON array (batch) or a JSON-RPC response body, reject with `400` and `-32600`. The spec gives no code for these cases; `-32600` is the JSON-RPC choice. Unparseable JSON gets `-32700` with no `id` and HTTP `400`.

### G6. `MCP-Protocol-Version` header: when it's missing and in what order to validate
The design's header rules are correct, but they don't cover these cases:
- The header is **required on every POST**. Crema doesn't support pre-`2025-06-18` clients, so it "**MUST** reject a request without the header per Server Validation", which gives `400` and `-32020` (streamable-http#protocol-version-header).
- If the header doesn't match `_meta.io.modelcontextprotocol/protocolVersion`, the result is `400` and `-32020`. It isn't `-32022`.
- If they match but the version isn't `2026-07-28`, the result is `400` and `-32022`.
- The spec doesn't set a precedence between these checks. The conformance suite (`server-stateless`) and its reference fixture (`everything-server.ts:1297-1349`) require this order:
  1. Parse the JSON (`-32700`).
  2. Check the JSON-RPC shape (`-32600`).
  3. Check that the standard headers are **present** (`-32020`).
  4. Check that `_meta`, `protocolVersion` and `clientCapabilities` are present (`-32602`). A body with no `protocolVersion` but a valid header must get **`-32602`**, not `-32020`.
  5. Check that the header values match the body (`-32020`). When both the `MCP-Protocol-Version` header and `_meta` say `v999.0.0`, the result is `-32022`. When the header says `2026-07-28` and `_meta` says `v999.0.0`, it's `-32020`, so the match check comes before the version check.
  6. Check the version is supported (`-32022`).
  7. Check the method exists (`404` with `-32601`).
- A legacy `initialize` request has no `_meta` and no `Mcp-Method`, so it fails in step 3. The spec wants a helpful message: a modern-only server "**SHOULD** name the protocol versions it supports in any error it returns to an `initialize` request" (basic/versioning#backward-compatibility-with-initialization-based-versions). Put `2026-07-28` in the message text, for example "Unsupported: this server speaks MCP 2026-07-28 only (stateless; no initialize)".

### G7. `prompts/get` and `completion/complete` error codes aren't specified in the design
- Unknown prompt, and a missing required prompt argument, both return `-32602` (server/prompts#error-handling, SHOULD). The design only specifies the unknown tool case.
- Prompt `arguments` are `{ [key: string]: string }` in the schema (`GetPromptRequestParams`). A non-string value is a malformed request and returns `-32602`.
- `completion/complete`:
  - An invalid prompt name returns `-32602` (server/utilities/completion#error-handling).
  - An unknown `ref.type` returns `-32602`.
  - A known ref whose argument has no Completion Method returns an empty result, `{"completion":{"values":[],"hasMore":false}}`, not an error. The spec doesn't say this directly. The Java SDK 2.0.1 release notes include the matching fix, "Return empty completion when no handler matches a valid ref" (PR #996).

### G8. Methods for capabilities that aren't advertised return `404` with `-32601`
> MethodNotFoundError: "a server returns this error when a client invokes a method the server does not implement — either a genuinely unknown method, or one gated behind a server capability the server did not advertise (e.g., calling `prompts/list` when the `prompts` capability was not advertised)." (schema.ts)

The design needs a rule for when `tools`, `resources`, `prompts` and `completions` are advertised. Two options:
- (a) Always advertise all four, and return empty lists when there's nothing to list.
- (b) Advertise a capability only when the MCP Server has at least one Feature of that kind, and return `404`/`-32601` for its methods otherwise.

With (b), role filtering must not change the advertised capabilities, because the list MAY vary by authorization but the capability set should not. Option (a) is simpler and makes the conformance runs easier. `subscriptions/listen` is `404`/`-32601` (the design already says this).

### G9. Tool `inputSchema` root must be `"type": "object"`
Schema: `inputSchema: { $schema?: string; type: "object"; ... }`. It "**MUST** be a valid JSON Schema object (not `null`)". For tools with no parameters, the recommended schema is `{ "type": "object", "additionalProperties": false }` (server/tools#tool). The generator must always emit an object root, even for zero Arguments. `outputSchema` can have any root type, including `array` (see the spec example). If `outputSchema` is present, "Servers **MUST** provide structured results that conform to this schema."

### G10. Invalid `cursor`
Crema doesn't paginate, but clients may still send `params.cursor` (`PaginatedRequestParams`). "Invalid cursors **SHOULD** result in an error with code -32602" (server/utilities/pagination). Since Crema never issues a cursor, any non-null `cursor` is invalid, so return `-32602` with the message `"Invalid cursor"`, which matches the schema example.

### G11. Rules for resource-not-found
Code `-32602` is already in the design. The spec adds:
- "Servers **MUST NOT** return an empty `contents` array for a non-existent resource."
- The canonical error shape carries `data.uri`:
  ```json
  {"jsonrpc":"2.0","id":5,"error":{"code":-32602,"message":"Resource not found","data":{"uri":"file:///nonexistent.txt"}}}
  ```
- "Servers **MUST NOT**" emit `-32002` (basic/index#error-codes).

### G12. HTTP status for errors that aren't transport errors isn't specified
The spec fixes HTTP status only for these cases:
- `400` for `-32020`, `-32021`, `-32022`, and for `-32602` caused by missing `_meta` fields.
- `404` for `-32601`.
- `403` for a bad `Origin`, and `401`/`403` for authorization.
- `405` for `GET`/`DELETE` (SHOULD).
- `202` for accepted notifications.

For every other JSON-RPC error, such as unknown tool `-32602`, bad prompt arguments or `-32603`, the spec gives no status. Crema should use `200` with the error body, the same as a result. The design is silent, so write this down.

### G13. Tool argument validation: the spec is ambiguous
- The tools page puts "Input validation errors (e.g., date in wrong format, value out of range)" under **Tool Execution Errors**, which means `isError: true`. That matches the design.
- The schema's `InvalidParamsError` example is `{"code":-32602,"message":"Invalid arguments for tool calculate: Missing required property 'expression'"}`. That shape is the other allowed reading.

The design's choice is fine. Just be aware that both readings exist.

### G14. `WWW-Authenticate` `scope` is a SHOULD that the design deliberately skips
- "MCP servers **SHOULD** include a `scope` parameter in the `WWW-Authenticate` header" (basic/authorization#scope-selection-strategy).
- For `403`: "`scope="required_scope1 required_scope2"` - specifying the minimum scopes needed" (basic/authorization#runtime-insufficient-scope-errors).

The design says "challenges carry no `scope` parameter". That's a conscious deviation from a SHOULD, so keep it listed as one.

### G15. Protected Resource Metadata location vs RFC 9728 well-known paths
The design serves the metadata at `<MCP Endpoint>/.well-known/oauth-protected-resource`, for example `/app/mcp/.well-known/oauth-protected-resource`. That isn't one of the RFC 9728 well-known locations the spec lists:
- `https://host/.well-known/oauth-protected-resource/app/mcp`
- `https://host/.well-known/oauth-protected-resource`

It's still compliant because the spec requires only one of two mechanisms, and Crema uses the `WWW-Authenticate` `resource_metadata` mechanism:
> "MCP servers **MUST** implement one of the following discovery mechanisms … 1. WWW-Authenticate Header … 2. Well-Known URI" (basic/authorization/authorization-server-discovery)

Two consequences:
- Clients that probe the well-known path without a `401` in hand won't find the metadata. A WAR under a context path can't serve host-root paths anyway.
- RFC 9728 §3.3 requires the metadata's `resource` value to be identical to the URL the client used when it got `resource_metadata` from a `WWW-Authenticate` header. Recent conformance commits check this for clients ("check the resource parameter matches the PRM-published identifier"). A configured `crema.*.resource` that differs from the actual endpoint URL will break spec-following clients. Treat that setting as "must equal the public endpoint URL", for example behind a reverse proxy, and not as an arbitrary audience string.

### G16. Smaller items
- **`progress` must increase.** "The `progress` value **MUST** increase with each notification" and "Progress notifications **MUST** stop after completion" (basic/patterns/progress). Crema should drop any non-increasing `send` and any `send` after the final response, or reject them with an exception.
- **`progressToken` type.** It is `string | number`. Echo it back verbatim, keeping the JSON type, so `1` stays `1` and doesn't become `"1"`.
- **Ignore MRTR parameters.** `inputResponses` and `requestState` may appear on `tools/call`, `resources/read` and `prompts/get` (`InputResponseRequestParams`). Crema doesn't use MRTR, so ignore them and never return `resultType: "input_required"`.
- **Never emit log notifications.** `io.modelcontextprotocol/logLevel` may be present. Crema never emits `notifications/message` and must not declare a `logging` capability.
- **`clientCapabilities` is per request.** "Servers **MUST NOT** infer capabilities from prior requests." `McpRequest.rawClientCapabilities()` must reflect only the current request, which the design already has.
- **Header values are case-sensitive.** "Header _values_ (such as method names) are case-sensitive." Header names are case-insensitive.
- **Header values with bad characters.** A header value with invalid characters returns `400` and `-32020`. After Base64-sentinel decoding, a value that isn't valid UTF-8 also returns `-32020`.
- **`Mcp-Name` for `resources/read`.** It mirrors `params.uri`, so compare the decoded header to the exact `uri` string without normalizing it.
- **`serverInfo` required fields.** `name` and `version` are both required in `Implementation`. The design's `"0.0.0"` fallback covers `version`.
- **Crema has no way to return `-32021`.** "If processing a request requires a capability the client did not include … the server **MUST** return a `MissingRequiredClientCapabilityError` (`-32021`) whose `data.requiredCapabilities` lists the missing capabilities" (basic/index#_meta). Crema itself never needs this, because it has no MRTR, sampling or elicitation. The conformance fixture `test_missing_capability`, however, requires the application to raise it, with `data.requiredCapabilities` as an object such as `{"sampling":{}}`. Crema therefore needs a way for a Feature Method to raise a JSON-RPC error with a given code and `data`, mapped to HTTP `400` for `-32021`. One option is an `McpException` carrying a code and data; this depends on what the `mcp-server-api` offers.
- **Removed methods return `404` with `-32601`.** These are `initialize`, `ping`, `logging/setLevel`, `resources/subscribe` and `resources/unsubscribe`. Conformance probes each of them.
- **Error responses must echo the request `id`.** The id may be omitted only when it couldn't be read.
- **Write JSON compactly.** Write each JSON response and each SSE `data:` payload as compact JSON on a single line. The conformance stream reader parses line by line. That's harness behaviour and not a spec rule, but pretty-printed output fails there.
- **`Accept` header.** The client "**MUST** include an `Accept` header listing both `application/json` and `text/event-stream`". The spec doesn't say what the server does when it's missing, so be lenient and don't return `406`.

---

## 1. Envelope
Source: https://modelcontextprotocol.io/specification/2026-07-28/basic (basic/index.mdx) and schema.ts.

### Request
```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
  "io.modelcontextprotocol/clientCapabilities":{},
  "io.modelcontextprotocol/clientInfo":{"name":"ExampleClient","version":"1.0.0"}}}}
```
- `id` is a `string | number` and "**MUST NOT** be `null`".
- `params` is required and carries `_meta` for every method in `ClientRequest`: `server/discover`, `completion/complete`, `prompts/get`, `prompts/list`, `resources/list`, `resources/templates/list`, `resources/read`, `subscriptions/listen`, `tools/call` and `tools/list`.

### Result response
```json
{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete", "...":"...",
  "_meta":{"io.modelcontextprotocol/serverInfo":{"name":"default","version":"1.0.0"}}}}
```
- `resultType` is **required**. The values are `"complete"`, `"input_required"` (MRTR) or a value added by an extension.
- `_meta.io.modelcontextprotocol/serverInfo` is a SHOULD on every result. Its type is `Implementation`.

### Error response
```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"...","data":{}}}
```
- `id` may be omitted only when it couldn't be read.
- `message` "SHOULD be limited to a concise single sentence".
- `data` is optional unless the specific error type requires it.

### Notification
```json
{"jsonrpc":"2.0","method":"notifications/progress","params":{...}}
```
A notification has no `id`, and `params._meta` is optional (`NotificationMetaObject`).

### Reserved `_meta` keys
| Key | Where | Type | Required |
|---|---|---|---|
| `io.modelcontextprotocol/protocolVersion` | request | `string` | **yes** |
| `io.modelcontextprotocol/clientCapabilities` | request | `ClientCapabilities` (`{}` = none) | **yes** |
| `io.modelcontextprotocol/clientInfo` | request | `Implementation` | no (client SHOULD send) |
| `io.modelcontextprotocol/logLevel` | request | `"debug"`, `"info"`, `"notice"`, `"warning"`, `"error"`, `"critical"`, `"alert"` or `"emergency"` | no (deprecated, SEP-2577) |
| `progressToken` | request | `string \| number` | no |
| `io.modelcontextprotocol/serverInfo` | result | `Implementation` | no (server SHOULD send) |
| `io.modelcontextprotocol/subscriptionId` | notification on a listen stream | `RequestId` | only on a listen stream |
| `traceparent`, `tracestate`, `baggage` | any | W3C formats | no |

- A prefix whose second label is `modelcontextprotocol` or `mcp` is reserved. This matters for Crema's `@MetaField` prefix validation: `io.modelcontextprotocol/`, `dev.mcp/` and `com.mcp.tools/` are reserved, but `com.example.mcp/` isn't.
- Key format: the optional prefix is dot-separated labels followed by `/`. Each label starts with a letter and ends with a letter or digit, with `[A-Za-z0-9-]` inside. The name begins and ends with `[A-Za-z0-9]`, with `[-_.A-Za-z0-9]` inside.
- `McpRequest.metadata()` should strip these reserved keys: the `io.modelcontextprotocol/*` keys, and arguably `progressToken`. `traceparent` is also reserved but is useful to apps.

### `Implementation` (clientInfo, serverInfo)
`{ name: string; title?: string; version: string; description?: string; websiteUrl?: string; icons?: Icon[] }`

`Icon`: `{ src: string (uri, https: or data:); mimeType?: string; sizes?: string[]; theme?: "light"|"dark" }`

### `ClientCapabilities`
`{ experimental?, roots?, sampling?: {context?, tools?}, elicitation?: {form?, url?}, extensions?: {[id]: object} }`

### `ServerCapabilities`
`{ experimental?, logging?, completions?: {}, prompts?: {listChanged?}, resources?: {subscribe?, listChanged?}, tools?: {listChanged?}, extensions? }`

---

## 2. Streamable HTTP
Source: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http

- **Endpoint.** There is one endpoint, and it accepts `POST` only. `GET` and `DELETE` SHOULD get `405`. `Mcp-Session-Id` is ignored, and the server never mints or echoes one. `Last-Event-ID` is ignored.
- **Response type.** For a request, the response is `Content-Type: application/json` (a single JSON-RPC response) or `text/event-stream`. On an SSE stream:
  - The server MAY send notifications that relate to the originating request.
  - It "**MUST NOT** send independent JSON-RPC _requests_ on this stream".
  - The final response "**SHOULD** terminate the stream".
  - The server SHOULD send `X-Accel-Buffering: no`.
  - No SSE event IDs are used, because streams can't be resumed.
- **Cancellation.** The client cancels by closing the stream (see G4).
- **`Origin`.** "Servers **MUST** validate the `Origin` header on all incoming connections". A present but invalid `Origin` gets `403`, and the body MAY be a JSON-RPC error with no `id`.

### Request headers
| Header | Source | Required for |
|---|---|---|
| `MCP-Protocol-Version` | `_meta["io.modelcontextprotocol/protocolVersion"]` | every POST |
| `Mcp-Method` | `method` | all requests |
| `Mcp-Name` | `params.name` (`tools/call`, `prompts/get`) or `params.uri` (`resources/read`) | those three methods |
| `Mcp-Param-{Name}` | tool argument marked `x-mcp-header` | only if the tool's schema declares it (Crema: out of scope) |

**Base64 sentinel.** A value of the form `=?base64?{Base64(UTF-8 bytes)}?=` must be decoded before comparing it to the body. The prefix and suffix are case-sensitive and must be exactly lowercase. Clients also encode any plain value that itself matches the sentinel pattern, so a decoded value is always authoritative.

Examples:
- `Mcp-Name: =?base64?SGVsbG8sIOS4lueVjA==?=` decodes to `Hello, 世界`.
- `=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=` decodes to the literal `=?base64?literal?=`.

**Validation failure returns `400` and `-32020`.** The failure cases are:
- a required standard header is missing;
- the header value (after decoding) doesn't match the body;
- the header value contains invalid characters.

When comparing `x-mcp-header` integer parameters, the server SHOULD compare them numerically. The error `data` is unspecified. Example:
```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"Header mismatch: Mcp-Name header value 'foo' does not match body value 'bar'"}}
```

### HTTP status summary
| Condition | HTTP | JSON-RPC |
|---|---|---|
| Result, or JSON-RPC error not listed below | 200 | result / error |
| Notification accepted | 202, empty body | none |
| Header missing, mismatched or malformed | 400 | `-32020` HeaderMismatch |
| Required `_meta` field missing | 400 | `-32602` |
| Version not supported | 400 | `-32022` UnsupportedProtocolVersion, `data:{supported,requested}` |
| Required client capability not declared | 400 | `-32021` MissingRequiredClientCapability, `data:{requiredCapabilities: ClientCapabilities}` |
| Unknown method, or method for a capability that isn't advertised | 404 | `-32601` |
| Bad `Origin` | 403 | optional error with no `id` |
| No or invalid token | 401 + `WWW-Authenticate: Bearer resource_metadata="…"` | none (not JSON-RPC) |
| Insufficient scope or role | 403 + `WWW-Authenticate: Bearer error="insufficient_scope", …` (SHOULD) | none |
| `GET` / `DELETE` | 405 | none |
| Parse error or invalid request | 400 is reasonable; the spec is silent | `-32700` / `-32600` (no `id` if it can't be read) |

For the Streamable HTTP backward-compatibility probe, the modern errors must be recognisable as JSON-RPC bodies on the `4xx` responses. Clients use them to decide not to fall back to `initialize`.

---

## 3. Versioning and `server/discover`
Sources:
- https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning
- https://modelcontextprotocol.io/specification/2026-07-28/server/discover

There is no handshake. Each request is accepted or rejected on its own, and "Servers **MUST** implement `server/discover`."

Request. There are no params besides `_meta`:
```json
{"jsonrpc":"2.0","id":"discover-1","method":"server/discover","params":{"_meta":{
  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
  "io.modelcontextprotocol/clientInfo":{"name":"ExampleClient","version":"1.0.0"},
  "io.modelcontextprotocol/clientCapabilities":{}}}}
```
Headers: `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method: server/discover`.

Result (`DiscoverResult extends CacheableResult`):
```json
{"jsonrpc":"2.0","id":"discover-1","result":{
  "resultType":"complete",
  "supportedVersions":["2026-07-28"],
  "capabilities":{"tools":{},"resources":{},"prompts":{},"completions":{}},
  "instructions":"…optional…",
  "ttlMs":3600000,
  "cacheScope":"public",
  "_meta":{"io.modelcontextprotocol/serverInfo":{"name":"default","title":"Order Service","version":"1.0.0"}}}}
```

| Field | Required | Notes |
|---|---|---|
| `supportedVersions` | yes | `string[]` |
| `capabilities` | yes | `ServerCapabilities` |
| `instructions` | no | |
| `ttlMs` | yes | ≥ 0 |
| `cacheScope` | yes | `"public"` or `"private"` |
| `resultType` | yes | |

Notes:
- A `discover` request with an unsupported version still gets `-32022`, because the version check applies to every request.
- For a protected MCP Server, the spec doesn't exempt `server/discover` from authorization, so it gets `401` like any other method.

---

## 4. Caching (`CacheableResult`)
Source: https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching

| Field | Type | Meaning |
|---|---|---|
| `ttlMs` | integer ≥ 0 | Freshness hint in milliseconds. `0` means immediately stale. |
| `cacheScope` | `"public"` or `"private"` | `private` means caches "**MUST NOT** be shared across authorization contexts". |

- Both fields are required on results of `server/discover`, `tools/list`, `prompts/list`, `resources/list`, `resources/templates/list` and `resources/read`.
- An `input_required` result carries neither field.
- `"private"` is appropriate for "filtered list results that vary per user" and for `resources/read` that depends on the user.
- Servers "MUST NOT rely on `cacheScope` alone to prevent unauthorized access". Crema's per-call role check stays necessary.

---

## 5. Tools
Source: https://modelcontextprotocol.io/specification/2026-07-28/server/tools

- **Capability:** `"tools": {}`, with `listChanged` omitted.
- **List contents:** the list "**MUST NOT** vary per-connection … **MAY** vary by the authorization presented on the request".
- **Order:** it SHOULD be deterministic.

### `tools/list`
Params: `{ _meta, cursor? }`

Result (`ListToolsResult`): `{ resultType, tools: Tool[], nextCursor?, ttlMs, cacheScope, _meta? }`
```json
{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[{
  "name":"get_weather",
  "title":"Weather Information Provider",
  "description":"Get current weather information for a location",
  "inputSchema":{"type":"object","properties":{"location":{"type":"string","description":"City name or zip code"}},"required":["location"]},
  "outputSchema":{"type":"object","properties":{"temperature":{"type":"number"}},"required":["temperature"]},
  "annotations":{"title":"Weather","readOnlyHint":true,"openWorldHint":true},
  "icons":[{"src":"https://example.com/weather-icon.png","mimeType":"image/png","sizes":["48x48"]}],
  "_meta":{"com.example/owner":"team-x"}}],
  "ttlMs":300000,"cacheScope":"public"}}
```

`Tool` fields:

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | SHOULD be 1–128 characters from `[A-Za-z0-9_.-]`, case-sensitive |
| `title` | string | no | |
| `description` | string | no | |
| `inputSchema` | `{ $schema?, type:"object", … }` | yes | Root `type:"object"` is mandatory. Any 2020-12 keyword is allowed, including `$defs`, `$ref` and composition keywords. Defaults to 2020-12 when `$schema` is absent. |
| `outputSchema` | `{ $schema?, … }` | no | Any root type |
| `annotations` | `ToolAnnotations` | no | See below |
| `icons` | `Icon[]` | no | |
| `_meta` | object | no | |

`ToolAnnotations` fields, all optional and all hints:

| Field | Type | Default |
|---|---|---|
| `title` | string | |
| `readOnlyHint` | boolean | false |
| `destructiveHint` | boolean | true |
| `idempotentHint` | boolean | false |
| `openWorldHint` | boolean | true |

Display name precedence is `title`, then `annotations.title`, then `name`.

### `tools/call`
Params (`CallToolRequestParams`):

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |
| `arguments` | `{[k]: unknown}` | no, so absence means no arguments |
| `inputResponses` | MRTR | no |
| `requestState` | MRTR | no |
| `_meta` | object | yes |

Headers: `Mcp-Method: tools/call`, `Mcp-Name: <name>`.

Result (`CallToolResult`): `{ resultType, content: ContentBlock[], structuredContent?: any JSON, isError?: boolean, _meta? }`.

`content` is required, so use `[]` for `void`.

```json
{"jsonrpc":"2.0","id":5,"result":{"resultType":"complete",
  "content":[{"type":"text","text":"{\"temperature\":22.5}"}],
  "structuredContent":{"temperature":22.5}}}
```

- A tool that returns structured content "SHOULD also return the serialized JSON in a TextContent block".
- Tool execution errors come back as `{"resultType":"complete","content":[{"type":"text","text":"…"}],"isError":true}`.
- An unknown tool is a protocol error: `{"code":-32602,"message":"Unknown tool: invalid_tool_name"}`.
- `tools/call` is **not** cacheable, so it carries no `ttlMs`.

### `ContentBlock` types
All of these can carry `annotations?` and `_meta?`.

| Type | Shape |
|---|---|
| `text` | `{"type":"text","text":"…"}` |
| `image` | `{"type":"image","data":"<base64>","mimeType":"image/png"}`; `data` and `mimeType` required |
| `audio` | `{"type":"audio","data":"<base64>","mimeType":"audio/wav"}` |
| `resource_link` | `{"type":"resource_link","uri":"…","name":"…","title?":"…","description?":"…","mimeType?":"…","size?":0,"icons?":[…]}`, the full `Resource` shape |
| `resource` | `{"type":"resource","resource":{"uri":"…","mimeType?":"…","text":"…"}}` or with `"blob":"<base64>"` in place of `text` |

`Annotations` (content, resources and templates): `{ audience?: ("user"|"assistant")[], priority?: number 0..1, lastModified?: ISO-8601 string }`.

---

## 6. Resources
Source: https://modelcontextprotocol.io/specification/2026-07-28/server/resources

- **Capability:** `"resources": {}`, with neither `subscribe` nor `listChanged`.

### `resources/list`
Params: `{ _meta, cursor? }`

Result: `{ resultType, resources: Resource[], nextCursor?, ttlMs, cacheScope }`

`Resource` fields:

| Field | Type | Required |
|---|---|---|
| `uri` | string | yes |
| `name` | string | yes |
| `title` | string | no |
| `description` | string | no |
| `mimeType` | string | no |
| `annotations` | `Annotations` | no |
| `size` | number (raw bytes) | no |
| `icons` | `Icon[]` | no |
| `_meta` | object | no |

### `resources/templates/list`
Params: `{ _meta, cursor? }`

Result: `{ resultType, resourceTemplates: ResourceTemplate[], nextCursor?, ttlMs, cacheScope }`

`ResourceTemplate` fields:

| Field | Type | Required |
|---|---|---|
| `uriTemplate` | string (RFC 6570) | yes |
| `name` | string | yes |
| `title` | string | no |
| `description` | string | no |
| `mimeType` | string | no |
| `annotations` | `Annotations` | no |
| `icons` | `Icon[]` | no |
| `_meta` | object | no |

### `resources/read`
Params: `{ _meta, uri, inputResponses?, requestState? }`. Headers: `Mcp-Method: resources/read`, `Mcp-Name: <uri>`.

Result: `{ resultType, contents: (TextResourceContents|BlobResourceContents)[], ttlMs, cacheScope }`
```json
{"jsonrpc":"2.0","id":2,"result":{"resultType":"complete",
  "contents":[{"uri":"file:///project/src/main.rs","mimeType":"text/x-rust","text":"fn main() {}"}],
  "ttlMs":0,"cacheScope":"public"}}
```
- `TextResourceContents` is `{ uri, mimeType?, text, _meta? }`.
- `BlobResourceContents` is `{ uri, mimeType?, blob (base64), _meta? }`.
- Multiple contents are allowed.
- A resource that isn't found returns `-32602`, with `data.uri` in the canonical example (see G11).
- Internal errors SHOULD be `-32603`.
- "Servers **MUST** validate all resource URIs" and "**MUST** sanitize file paths" for `file://`.

---

## 7. Prompts
Source: https://modelcontextprotocol.io/specification/2026-07-28/server/prompts

- **Capability:** `"prompts": {}`

### `prompts/list`
Params: `{ _meta, cursor? }`

Result: `{ resultType, prompts: Prompt[], nextCursor?, ttlMs, cacheScope }`

`Prompt` fields:

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |
| `title` | string | no |
| `description` | string | no |
| `arguments` | `PromptArgument[]` | no |
| `icons` | `Icon[]` | no |
| `_meta` | object | no |

`PromptArgument` fields:

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |
| `title` | string | no |
| `description` | string | no |
| `required` | boolean | no |

```json
{"name":"code_review","title":"Request Code Review","description":"…",
 "arguments":[{"name":"code","description":"The code to review","required":true}]}
```

### `prompts/get`
Params: `{ _meta, name, arguments?: {[k]: string}, inputResponses?, requestState? }`. Headers: `Mcp-Method: prompts/get`, `Mcp-Name: <name>`.

Result: `{ resultType, description?, messages: PromptMessage[] }`. `PromptMessage` is `{ role: "user"|"assistant", content: ContentBlock }`, with exactly one block, not an array. The result isn't cacheable.
```json
{"jsonrpc":"2.0","id":2,"result":{"resultType":"complete","description":"Code review prompt",
  "messages":[{"role":"user","content":{"type":"text","text":"Please review this Python code: …"}}]}}
```
Errors: an invalid prompt name or a missing required argument returns `-32602`. An internal error returns `-32603`.

---

## 8. Completion
Source: https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/completion

- **Capability:** `"completions": {}`

### `completion/complete`
Params (`CompleteRequestParams`):

| Field | Type | Required |
|---|---|---|
| `ref` | `{"type":"ref/prompt","name":string,"title?":string}` or `{"type":"ref/resource","uri":string}` | yes |
| `argument` | `{ name: string, value: string }` | yes |
| `context` | `{ arguments?: {[k]: string} }` | no |
| `_meta` | object | yes |

For `ref/resource`, the `uri` is the **template** string, for example `"file:///{path}"`. It isn't an expanded URI. It "is a URI or URI template", so match it against a Resource Template's `uriTemplate` exactly.

```json
{"jsonrpc":"2.0","id":1,"method":"completion/complete","params":{
  "_meta":{…},
  "ref":{"type":"ref/prompt","name":"code_review"},
  "argument":{"name":"framework","value":"fla"},
  "context":{"arguments":{"language":"python"}}}}
```
Result:
```json
{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","completion":{"values":["flask"],"total":1,"hasMore":false}}}
```
- `values` is required, with at most 100 items. `total` and `hasMore` are optional.
- The result isn't cacheable.
- There is no `Mcp-Name` header for this method.
- Errors: `-32601` if the capability isn't supported, `-32602` for an invalid prompt name or missing required arguments, `-32603` for an internal error.

---

## 9. Progress
Source: https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/progress

- **Opt-in.** The client opts in per request with `params._meta.progressToken` (`string | number`). This is **not** the `io.modelcontextprotocol/` prefix; the key is bare, as before.
  ```json
  {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"slow","arguments":{},"_meta":{
    "progressToken":"abc123","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}
  ```
- **Notification.** The server sends it on the SSE response stream of **that** request only, never on a `subscriptions/listen` stream (changelog, major change 4):
  ```json
  {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"abc123","progress":50,"total":100,"message":"Reticulating splines..."}}
  ```

  | Field | Type | Required |
  |---|---|---|
  | `progressToken` | `string \| number` | yes |
  | `progress` | number | yes |
  | `total` | number | no |
  | `message` | string | no |
  | `_meta` | object | no |
- **Rules:**
  - `progress` **MUST** increase with each notification, and may be a float.
  - A notification may only reference a token from an active, in-progress request.
  - Notifications "**MUST** stop after completion".
  - The server MAY send none at all.
- **SSE framing.** Each message is one `data:` line holding the JSON, followed by a blank line. The `event:` field is omitted or set to `message`. There's no `id:`, because streams can't be resumed.

---

## 10. Error codes
Sources: basic/index#error-codes and schema.ts

| Code | Name | `data` | HTTP (Streamable HTTP) |
|---|---|---|---|
| -32700 | Parse error | none | not specified (use 400) |
| -32600 | Invalid request | none | not specified (use 400) |
| -32601 | Method not found | optional; the example has `{"reason":"…"}` | **404** |
| -32602 | Invalid params: unknown tool or prompt, bad or missing prompt args, invalid cursor, resource not found (`data:{uri}`), missing required `_meta` | optional | **400** only for missing `_meta` fields; otherwise not specified (use 200) |
| -32603 | Internal error | optional | not specified (use 200) |
| -32020 | HeaderMismatch | not specified | **400** |
| -32021 | MissingRequiredClientCapability | **required** `{"requiredCapabilities": ClientCapabilities}` | **400** |
| -32022 | UnsupportedProtocolVersion | **required** `{"supported": string[], "requested": string}` | **400** |

- `-32000` to `-32019` is legacy: "new implementations **SHOULD NOT** use codes from this sub-range".
- `-32020` to `-32099` is reserved for the spec. Implementations "**MUST NOT** emit any code from this sub-range that is not defined by this specification".
- `-32002` and `-32042` "**MUST NOT**" be emitted.
- App-defined codes go outside `-32768` to `-32000`.

`-32021` example (Crema never needs it, because it requires no client capability):
```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32021,"message":"Server requires the elicitation capability for this request",
 "data":{"requiredCapabilities":{"elicitation":{}}}}}
```

---

# Test tooling

## T1. Official MCP conformance suite: yes, it tests servers at 2026-07-28 over Streamable HTTP

Sources:
- Repo: https://github.com/modelcontextprotocol/conformance. Checked at `main` @ `7169291`, 2026-09-11.
- npm: https://www.npmjs.com/package/@modelcontextprotocol/conformance

### Which version to run
- The npm dist-tag `latest` is **`0.1.16`**. It has no `--requirements` option, so it can't target the stateless wire.
- Use **`0.2.0-alpha.11`**, dist-tag `alpha`, published 2026-08-07. It supports `--requirements`, `--spec-version` and `--force`.
- `requirements/2026-07-28.yaml` is the frozen set of scenarios that the 2026-07-28 revision requires. It is anchored at `0.2.0-alpha.10`.

### How the harness talks to the server
At 2026-07-28 the harness speaks the stateless wire:
- There is no `initialize`, and it doesn't call `server/discover` first.
- Every request is a `POST` with these headers: `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method`, `Mcp-Name`, and `Accept: application/json, text/event-stream`.
- Every request carries `_meta` with `protocolVersion`, `clientInfo` (`conformance-test-client`/`1.0.0`) and `clientCapabilities`. The capabilities are usually `{sampling:{}, elicitation:{}, roots:{listChanged:true}}`, and `{}` in the raw probes.
- It never sends `GET`, `DELETE` or `Mcp-Session-Id` at this version (`src/connection/stateless.ts`).

### Command
The server has to be running already, for example a WAR deployed at `/app` with its MCP endpoint at `/app/mcp`:
```bash
npx -y @modelcontextprotocol/conformance@0.2.0-alpha.11 server \
  --url http://localhost:8080/app/mcp \
  --requirements 2026-07-28 \
  --expected-failures crema-it/src/test/conformance/baseline-2026-07-28.yml \
  -o target/conformance
```
- **Conflicting options.** `--requirements` is mutually exclusive with an explicit `--scenario`, `--suite` or `--spec-version`; combining them exits 1.
- **Running one scenario.** Use `--scenario <id> --spec-version 2026-07-28` instead.
- **Timeout.** `--timeout <ms>` is per scenario and defaults to 30000. Each HTTP request has its own 10 s limit.
- **Exit code.**
  - The run exits 1 only when a *scored* scenario has a FAILURE that isn't in the baseline. WARNINGs never fail the run.
  - A baseline entry that now passes is *stale*, and that also exits 1.
  - A baseline entry can name a single check: `- scenario:check-id`, with no space after the colon.
- **Output.**
  - With `-o`, the results are written to `<dir>/server-<scenario>-<timestamp>/checks.json`.
  - Every scenario also gets a synthetic **`wire-schema-valid`** check. It validates every JSON-RPC message the server sends against the 2026-07-28 `schema.json`. So a result missing `resultType`, or a cacheable result missing `ttlMs` or `cacheScope`, fails nearly every scenario.
- **The URL host must be local.** It has to be `localhost`, `127.0.0.1` or `[::1]`, otherwise `dns-rebinding-protection` fails.

### Scored server scenarios for 2026-07-28
`npx … list --requirements 2026-07-28` lists these 37:
- `server-stateless`
- `completion-complete`
- `tools-list`
- `tools-call-simple-text`, `tools-call-image`, `tools-call-audio`, `tools-call-embedded-resource`, `tools-call-mixed-content`, `tools-call-error`, `tools-call-with-progress`
- `server-sse-multiple-streams`
- `resources-list`, `resources-read-text`, `resources-read-binary`, `resources-templates-read`
- `sep-2164-resource-not-found`
- `prompts-list`, `prompts-get-simple`, `prompts-get-with-args`, `prompts-get-embedded-resource`, `prompts-get-with-image`
- `dns-rebinding-protection`
- `caching`
- 14 × `input-required-result-*` (MRTR)

Scenarios that run but aren't scored:
- `tasks-*`: an extension.
- `json-schema-2020-12`, `http-header-validation`, `http-custom-header-server-validation`: pending.

### Fixtures the server under test must expose
The names follow the reference server `examples/servers/typescript/everything-server.ts`. A missing fixture is a **FAILURE** ("Not testable: …") and is never skipped (`src/scenarios/untestable.ts`).
- Every tool and prompt must have a `description`. `tools-list` and `prompts-list` fail without one.
- Tool `inputSchema` is `{"type":"object","properties":{}}` unless stated otherwise.

**Tools**

| Name | Arguments | Must return | Scenario |
|---|---|---|---|
| `test_simple_text` | none; called with **no** `arguments` field | a `text` block with non-empty text, e.g. "This is a simple text response for testing." | tools-call-simple-text |
| `test_image_content` | none | an `image` block with `data` and `mimeType` (fixture: `image/png`, 1×1 PNG `iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==`) | tools-call-image |
| `test_audio_content` | none | an `audio` block, `mimeType` **exactly** `audio/wav` (fixture data `UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAA=`) | tools-call-audio |
| `test_embedded_resource` | none | a `resource` block with `uri`, `mimeType` and `text` or `blob` (fixture: `test://embedded-resource`, `text/plain`, "This is an embedded resource content.") | tools-call-embedded-resource |
| `test_multiple_content_types` | none | at least 2 blocks, including text, image and resource (fixture: text "Multiple content types test:", the PNG, and resource `test://mixed-content-resource` `application/json` `{"test":"data","value":123}`) | tools-call-mixed-content |
| `test_error_handling` | none | a **result** with `isError: true` and non-empty `content[0].text`, not a JSON-RPC error | tools-call-error |
| `test_tool_with_progress` | none; request `_meta.progressToken = "progress-test-1"` | at least 3 `notifications/progress` echoing the token, with non-decreasing `progress` (fixture 0, 50, 100 with `total` 100), **as `text/event-stream`**, then the result | tools-call-with-progress |
| `test_missing_capability` | none | `-32021`, HTTP 400, `data.requiredCapabilities = {"sampling":{}}` when the request's `clientCapabilities` lacks `sampling`; text "Success" otherwise | server-stateless |
| `test_streaming_elicitation` | none | any plain result; the stream must not contain server→client requests | server-stateless |
| `test_logging_tool` | none | any plain result; no `notifications/message` without `logLevel` | server-stateless |
| `test_input_required_result_*`, and prompt `test_input_required_result_prompt` | | MRTR `input_required` results; not feasible for Crema | input-required-result-* |

**Resources**

| URI / template | Must return | Scenario |
|---|---|---|
| `test://static-text` | `contents[0]` with `uri`, `mimeType` and `text` (fixture `text/plain`, "This is the content of the static text resource.") | resources-read-text, caching (reads the first listed resource) |
| `test://static-binary` | `contents[0]` with `uri`, `mimeType` and `blob` (fixture `image/png`, the PNG above) | resources-read-binary |
| template `test://template/{id}/data` | reading `test://template/123/data` returns **text** containing `123` (fixture `application/json` `{"id":"123","templateTest":true,"data":"Data for ID: 123"}`) | resources-templates-read |
| none | `resources/read` of `test://nonexistent-resource-for-conformance-testing` returns error `-32602` (WARN otherwise) with `data.uri` equal to the URI (WARN), and must not return empty contents (FAIL) | sep-2164-resource-not-found |

**Prompts**

| Name | Arguments | Must return | Scenario |
|---|---|---|---|
| `test_simple_prompt` | none | non-empty `messages` with `role` and `content` | prompts-get-simple |
| `test_prompt_with_arguments` | `arg1`, `arg2`, both required | messages containing both values, e.g. `Prompt with arguments: arg1='testValue1', arg2='testValue2'` | prompts-get-with-args |
| `test_prompt_with_embedded_resource` | `resourceUri`, required | a message with a `resource` block `{uri:<arg>, mimeType:"text/plain", text:"Embedded resource content for testing."}` | prompts-get-embedded-resource |
| `test_prompt_with_image` | none | a message with an `image` block (`data`, `mimeType`) | prompts-get-with-image |

**Completion.** `completion/complete` with `ref: {type:"ref/prompt", name:"test_prompt_with_arguments"}` and `argument: {name:"arg1", value:"test"}` must return a `completion.values` array. An empty array passes.

### Protocol checks in the stateless and transport scenarios
These come from `server-stateless`, `caching`, `dns-rebinding-protection` and `server-sse-multiple-streams`. Everything is FAIL level unless noted.

- **Missing `_meta` fields.** A missing `_meta`, `protocolVersion` or `clientCapabilities` gets `400` with `-32602`. A missing `clientInfo` still gets a result. The error must echo the request `id`.
- **`server/discover`.** It returns `supportedVersions` and `capabilities`. `serverInfo` in the result `_meta` is a WARN-level check.
- **Declared capabilities must match the methods served.**
  - If `tools` is declared, `tools/list` must work; if it isn't declared, `tools/list` must return `-32601`.
  - If `prompts/list` doesn't return `-32601`, then `capabilities.prompts` must be declared.
- **Unsupported version.** Header and `_meta` both set to `v999.0.0` gets `400` and `-32022`. `data.supported` must be non-empty and a subset of discover's `supportedVersions`, and `data.requested` must be `"v999.0.0"`.
- **Version mismatch.** Header `2026-07-28` with `_meta` `v999.0.0` gets `400` and `-32020`.
- **Removed and unknown methods.** `initialize`, `ping`, `logging/setLevel`, `resources/subscribe`, `resources/unsubscribe` and `unknown/method` each get `404` and `-32601`.
- **`subscriptions/listen`.** It returns `-32601` as a single-line JSON body. The subscription checks are then **SKIPPED**, but only if discover advertises no `listChanged` or `subscribe`.
- **`caching` scenario.** `ttlMs` (integer ≥ 0) and `cacheScope` must be on all four lists and on `resources/read`.
- **`dns-rebinding-protection`.** `Host`/`Origin` set to `evil.example.com` must get any `4xx`, and `localhost:8080` must get a `2xx` (see G0).
- **`server-sse-multiple-streams`.** Three concurrent `tools/list` POSTs must all get a `2xx`. With JSON responses, the second check is INFO.

### MRTR scenarios
Crema has no MRTR, so the 14 `input-required-result-*` scenarios come out as follows. They have no capability gate and no skip.
- **10 FAIL:** `basic-elicitation`, `basic-sampling`, `basic-list-roots`, `request-state`, `multiple-input-requests`, `multi-round`, `non-tool-request`, `result-type`, `tampered-state`, `capability-check`.
- **2 WARN:** `missing-input-response`, `ignore-extra-params`.
- **2 pass:** `unsupported-methods` and `validate-input`, because an unknown tool gets `-32602`.

Put exactly those 10 in the baseline:
```yaml
server:
  - input-required-result-basic-elicitation
  - input-required-result-basic-sampling
  - input-required-result-basic-list-roots
  - input-required-result-request-state
  - input-required-result-multiple-input-requests
  - input-required-result-multi-round
  - input-required-result-non-tool-request
  - input-required-result-result-type
  - input-required-result-tampered-state
  - input-required-result-capability-check
```

### Not scored, but worth running
- **`http-header-validation`** is Crema's own header logic. `400` is FAIL level and `-32020` is WARN level. It sends:
  - `Mcp-Method` mismatched with the body, missing, or in a different case (`TOOLS/LIST` must be rejected);
  - `Mcp-Name` mismatched or missing;
  - header *names* in lower or upper case, which must be accepted;
  - `Mcp-Name` with surrounding whitespace, which must be **accepted** (trim the header value before comparing).

  It then calls the first listed tool with `{}`.
- **`json-schema-2020-12`** needs a tool `json_schema_2020_12_tool` whose `inputSchema` keeps `$schema`, `$defs`/`$anchor`, `allOf`/`anyOf` and `if`/`then`/`else` verbatim. Crema generates schemas, so it's unlikely to pass.
- **`http-custom-header-server-validation`** needs `x-mcp-header`, which Crema doesn't implement.

## T2. Official MCP Java SDK: no support for 2026-07-28

Sources:
- https://github.com/modelcontextprotocol/java-sdk
- Maven Central `io.modelcontextprotocol.sdk`

**Latest release.** It is **`io.modelcontextprotocol.sdk:mcp:2.0.1`**, released 2026-08-19. The BOM is `io.modelcontextprotocol.sdk:mcp-bom:2.0.1`, and the modules are `mcp-core`, `mcp-json-jackson2` and `mcp-json-jackson3`. The 1.1.x maintenance line is at `1.1.4`.

**No stateless support.**
- `ProtocolVersions` ends at `MCP_2025_11_25` on `main` @ `c7fef64f` (2026-09-17, version `2.1.0-SNAPSHOT`).
- Its client transports offer at most `2025-11-25` (`McpTransport.protocolVersions()`), and the client always runs the `initialize` handshake.
- There is no `server/discover`, no per-request `_meta` protocol fields, no `resultType`, and no `ttlMs` or `cacheScope`.
- Tracking issues are open: #1011 "SEP-2575: Make MCP Stateless" (P1), #1009 SEP-2549 TTL, #1001 SEP-2164. PR #1112 (SEP-2243 headers) is open.

**Consequence.** The Java SDK client can't talk to a 2026-07-28-only server. Its `initialize` gets `400` with `-32020`, because the MCP headers are missing.

## T3. Recommendation for `crema-it`

1. **Primary: plain HTTP tests.** Write the tests with `java.net.http.HttpClient` and JSON-P against the deployed WAR, plus a small helper that adds the headers and `_meta`.
   - This is the only way to cover the error paths (G0–G16 and §2 status table), the security cases (`401`, `403`, PRM, Origin) and exact status codes.
   - Assert that every result validates against `schema/2026-07-28/schema.json`, using any JSON Schema 2020-12 validator in test scope. This is what the conformance suite's `wire-schema-valid` check does.
2. **Conformance: a conformance fixture WAR.** Deploy a test WAR that implements exactly the fixtures in T1, on one Runtime at least. The fixture needs a way to raise `-32021` (see G16).
   - Run `@modelcontextprotocol/conformance@0.2.0-alpha.11 server --requirements 2026-07-28` against `http://localhost:<port>/<ctx>/mcp`. Use the MRTR baseline above. The run also reports the not-scored `http-header-validation` results.
   - Drive it from Maven with `frontend-maven-plugin` or `exec-maven-plugin` (`npx`) in `integration-test`, after Arquillian or Cargo has started the container. The simplest way is a JUnit test that shells out to `npx` and asserts exit code 0; it needs Node ≥ 20 on the build machine.
   - Pin the exact alpha version. Moving to a newer version is a deliberate change, because newer suites add checks such as deterministic `tools/list` ordering.
3. **Don't depend on the MCP Java SDK** as a test client until a release ships the SEP-2575 stateless client. Re-check issue #1011.

