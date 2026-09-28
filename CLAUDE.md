# Crema

MCP server implementation for Jakarta EE. What to build: [docs/design.md](docs/design.md). Vocabulary: [CONTEXT.md](CONTEXT.md), so use its terms in code and docs. Decisions: [docs/adr/](docs/adr/).

## Build

- Run Maven with JDK 21. Check `java -version` after switching; `sdk use` prints nothing when a candidate isn't installed.
- `mvn -pl crema verify` runs the unit tests. `mvn -pl crema-it verify -P<runtime>` runs the integration tests for one Runtime (`tomee`, `openliberty`, `wildfly`, `payara`).
- Bytecode targets Java 17: don't use APIs newer than Java 17 in `crema/src/main`.

## Code conventions

- Only Jakarta EE 10 Web Profile APIs, `org.mcpjava:mcp-server-api` and `com.nimbusds:nimbus-jose-jwt`. MicroProfile Config is optional and must be guarded by class-presence checks.
- Never hand-write security-sensitive code (token parsing, signatures, claim validation, JWKS handling): delegate it to Nimbus.
- Everything under `io.github.jungm.crema.internal` is internal. Public API is only `io.github.jungm.crema`.
- Comments describe the code as it is, in present tense. No change narration ("was", "now also", "moved from").
- JSON: JSON-P for the protocol envelope, JSON-B for application values (Arguments, return values, structured content). Crema's own `Jsonb` instance; never the application's.
- Fail at deployment rather than at call time whenever a problem is detectable from annotations.
