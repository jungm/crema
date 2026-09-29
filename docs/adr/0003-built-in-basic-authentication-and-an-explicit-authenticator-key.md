# Built-in Basic authentication, and an explicit `authenticator` key

Crema offers HTTP Basic authentication against users in MicroProfile Config, and each MCP Server says how it authenticates callers with the key `authenticator`: `oauth`, `basic` or `bean` (its `McpAuthenticator`). A protected MCP Server must set it.

Internally, OAuth, Basic and the `McpAuthenticator` bean are three implementations of one mechanism interface, which authenticates a request and shapes its `401`, `403` and Protected Resource Metadata answers. Basic needs its own challenge (`WWW-Authenticate: Basic realm=…`), which the public `McpAuthenticator` can't express, so it is built in rather than an `McpAuthenticator`.

## Considered Options

- **Plaintext passwords** (chosen), PBKDF2 hashes verified by Jakarta Security's `Pbkdf2PasswordHash`, or both. Plaintext is the simplest to set up and needs no tool to generate hashes. The price is that anyone who can read the configuration can read the passwords. The documentation points to configuration sources outside the WAR, such as environment variables. This refines ADR 0002, which rejected configuration-only authentication partly because of hashing: Crema accepts that trade-off for Basic. Trusted identity headers stay rejected.
- **Selecting the mechanism by which keys are set** (an `issuer` means OAuth, a bound bean means `bean`): rejected for protected MCP Servers. An explicit key makes a protected MCP Server's security readable in one place, and a typo in a key name can't silently switch the mechanism. As a consequence, protected MCP Servers need MicroProfile Config even with an `McpAuthenticator` bean. Open MCP Servers still use a bound bean without the key, since that adds callers and takes no protection away.
- **Constant-time comparison of the passwords themselves**: `MessageDigest.isEqual` takes time proportional to the configured password's length. Comparing SHA-256 digests takes the same time whatever the passwords are. An unknown user is compared against a dummy digest, so that it takes as long as a wrong password.

## Consequences

- An MCP Server can be protected with a few properties, and no Authorization Server or code.
- Every failure of Basic authentication gets the same `401`, so responses don't tell whether a user exists.
- Every protected MCP Server's configuration names its mechanism. Existing OAuth configurations need `authenticator=oauth`.
