# Building and testing

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
mvn -B -pl crema-it -am verify -Ppayara
```

`ConformanceIT` runs the official MCP conformance suite (`@modelcontextprotocol/conformance`) against a fixture WAR;
it needs Node.js 20 or newer. Run a single test class with `-Dit.test=ConformanceIT`. See
[crema-it/README.md](../../crema-it/README.md) for ports, Runtime versions and what each test covers.

Further reading: [docs/design.md](../design.md) (what Crema does, in detail), [CONTEXT.md](../../CONTEXT.md) (vocabulary)
and [docs/protocol-notes.md](../protocol-notes.md) (the protocol, from the server's side).
