# Limitations

Not supported (yet):

- Protocol: only revision `2026-07-28` over Streamable HTTP. No older handshake-based revisions, no stdio transport.
- Cancellation: `Cancellation` never fires, and a client closing the stream doesn't stop the Feature Method.
- `subscriptions/listen` and change notifications (`listChanged`, resource subscriptions).
- Multi-round-trip requests (MRTR), elicitation, sampling and logging.
- Pagination of lists; everything is returned in one page.
- `x-mcp-header` / `Mcp-Param-*` headers.
- CORS: browser-based MCP Clients on a foreign origin need an allowlisted origin, and Crema answers no preflights.
- OAuth: one issuer per MCP Server; JWT access tokens only (no token introspection); scopes aren't mapped to roles,
  and `403` challenges carry no `scope`.
- Packaging: Crema must be in each WAR's `WEB-INF/lib`. It can't be shared through an EAR's `lib/` directory or a
  Runtime-level shared library, and each WAR needs CDI enabled.
