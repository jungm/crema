# Configuration

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
| `<prefix>scopes` | none | Comma-separated OAuth scopes MCP Clients should request, sent as `scopes_supported` and in `401` challenges |
| `crema.origin.allowed` | empty (loopback only) | Comma-separated `Origin`s that may call the MCP Endpoints; `*` disables the check |
| `crema.cache.list-ttl-ms` | `300000` | `ttlMs` caching hint on `server/discover` and list results |
| `crema.max-request-bytes` | `4194304` | Largest accepted request body; larger ones get `413` |
