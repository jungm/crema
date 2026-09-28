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
| `tomee`             | Apache TomEE 10.2.0, `microprofile` flavor | the adapter resolves `apache-tomee:zip:microprofile` via Maven into `target/tomee` | `org.apache.tomee:arquillian-tomee-remote` 10.2.0              | http 18080, shutdown 18005, no AJP      | ~15 s     |
| `wildfly`           | WildFly 41.0.1.Final, `standalone-microprofile.xml` | `maven-dependency-plugin` unpacks `wildfly-dist` into `target/` | `org.wildfly.arquillian:wildfly-arquillian-container-managed` 5.1.0.Final | port offset 10100 (http 18180, management 20090) | ~20 s     |
| `openliberty`       | Open Liberty 26.0.0.9 (`openliberty-runtime`) | `maven-dependency-plugin` unpacks into `target/liberty`, `src/test/liberty/server.xml` is copied (filtered) | `io.openliberty.arquillian:arquillian-liberty-managed-jakarta` 3.0.0 | http 18280, https 18643                 | ~30 s     |
| `websphere-liberty` | WebSphere Liberty 26.0.0.4 (`wlp-jakartaee10`, latest on Maven Central) | same as `openliberty`                                       | same as `openliberty`                                           | http 18380, https 18743                 | ~25 s     |

Times are for `mvn clean verify` with the Runtime already in the local Maven repository. Each adapter stops its
server at the end of the run. Liberty uses `webProfile-10.0`, `mpConfig-3.1`, `mpJwt-2.1` and `localConnector-1.0`.

Each test records its observations as `FINDING` lines on stdout and in `target/findings/<runtime>-<test>.txt`.

## Security coexistence (ADR 0001)

`SecurityCoexistenceIT` deploys one WAR (built by `CoexistenceWar`) containing:

- `/mcp`: a JAX-RS application shaped like `McpApplication` (abstract base with a `final getClasses()`), with
  `@LoginConfig(authMethod = "MP-JWT")` and no role constraints; its resource reports the caller.
- `/api`: a scanning JAX-RS application with `@LoginConfig(authMethod = "MP-JWT")` and a `@RolesAllowed("user")`
  resource.
- `/ui`: a servlet with `@ServletSecurity(@HttpConstraint(rolesAllowed = "user"))`, protected by
  `@OpenIdAuthenticationMechanismDefinition` against a fake OpenID Connect provider (discovery + JWKS) that runs in
  the test JVM. Its `providerURI` comes from MP Config through EL.
- MP-JWT configured in `META-INF/microprofile-config.properties` (`mp.jwt.verify.publickey` as inline PEM, issuer,
  two audiences). Tests mint RS256 tokens with JDK crypto.

`LoginConfigNextToMechanismIT` deploys the same WAR with `@LoginConfig` on every Runtime and records whether it
deploys.

Runtime-specific parts of the WAR, all switched by the profiles:

- WildFly: an EE 11 `HttpAuthenticationMechanismHandler` (`PathMechanismHandler`), and the web.xml context parameter
  `resteasy.role.based.security=true` (present on every Runtime, ignored by the others).
- Liberty: no `@LoginConfig` on the JAX-RS applications.
- All: the `/mcp` application class is `@Dependent` (see Liberty in the table).

### Findings

| # | Check | TomEE 10.2.0 | WildFly 41.0.1 | Open Liberty 26.0.0.9 / WebSphere Liberty 26.0.0.4 |
|---|-------|--------------|----------------|----------------------------------------------------|
| 1 | WAR with MP-JWT and the OIDC mechanism deploys | Yes. | Deploys, but without an application-supplied `HttpAuthenticationMechanismHandler` (Jakarta Security 4.0) every request fails with 500: SmallRye JWT registers MP-JWT as a Jakarta Security mechanism bean (`JWTHttpAuthenticationMechanism`), so Soteria's default handler finds two mechanisms (WELD-001318). With the handler: yes. | With `@LoginConfig`: no, CWWKS1931E ("both a login-config … and the HttpAuthenticationMechanism"); Liberty turns `@LoginConfig` into a login configuration and allows none next to a mechanism. Without `@LoginConfig`: yes. |
| 2 | `/mcp` without token | 200 from the resource. `getUserPrincipal()` throws `MPJWTFilter$MissingAuthorizationHeaderException`; uncaught, it turns into a Runtime 401. | 200 from the resource, anonymous. | 200 from the resource, anonymous. |
| 3 | `/mcp` with valid token | `JsonWebToken` principal, `aud` visible, `groups` mapped to roles. | Same. | **Token ignored**, caller anonymous. With a Jakarta Security mechanism in the module, Liberty runs the mechanism (JASPIC bridge) for every request and never consults the mpJwt trust association interceptor. |
| 4 | `/mcp` with expired / wrong issuer / wrong signature / unlisted `aud` / malformed token | Runtime 401 before the resource, no `WWW-Authenticate`. | Runtime 401 before the resource, no `WWW-Authenticate`. | 200, anonymous (token ignored, see 3). |
| 5 | `/api` (`@RolesAllowed("user")`), without token / valid / valid without role / expired | 401 / 200 / 403 / 401 | 403 / 200 / 403 / 401. Needs `resteasy.role.based.security`; RESTEasy answers 403 for anonymous callers. | 403 / **403** / 403 / 403 (token ignored, see 3). |
| 6 | `/ui` without login | 302 to the provider's authorization endpoint. | Same. | Same. |
| 7 | Application's scanned `@Provider` applied to `/mcp` | **Yes.** TomEE hands discovered providers to every `Application` in the WAR. | No. | No. |

Further observations:

- Liberty without a Jakarta Security mechanism (observed on Open Liberty with the OIDC classes removed from the WAR):
  MP-JWT works with or without `@LoginConfig` (`mpJwt ignoreApplicationAuthMethod` defaults to `true`). A valid token
  on `/mcp` gives a `JsonWebToken`; invalid tokens on `/mcp` leave the caller anonymous (the interceptor logs
  CWWKS5523E); `/api` answers 403 / 200 / 403 / 403. `mp.jwt.verify.publickey` must be a PEM with header and footer;
  Liberty rejects the bare Base64 key that TomEE and WildFly accept.
- Liberty makes JAX-RS `Application` subclasses CDI beans with a normal scope, so a `final` method in the
  `Application` hierarchy fails with WELD-001480 (unproxyable) on the first request (observed on Open Liberty).
  `@Dependent` on the subclass avoids the proxy.
- TomEE: if any `Application` class in the WAR declares methods itself, TomEE skips discovered providers for all
  JAX-RS applications of the WAR, including MP-JWT's `@RolesAllowed` feature (`/api` then answers 200 to a token
  without the role). Inherited methods don't count, so `McpApplication` subclasses that declare nothing are fine.
  The switch is `openejb.jaxrs.providers.auto`.

### What this means for ADR 0001

- **Liberty: MP-JWT and a Jakarta Security mechanism cannot share a WAR.** With `@LoginConfig` deployment fails;
  without it bearer tokens are ignored. The Liberty source has no switch for either (the check is in
  `JavaEESecCDIExtension.verifyConfiguration`; the mechanism-first dispatch in
  `WebAppSecurityCollaboratorImpl.performSecurityChecks`). A protected
  MCP Server on Liberty needs a WAR without Jakarta Security mechanisms, or a UI login configured in `server.xml`
  (`openidConnectClient`, untested) instead of `@OpenIdAuthenticationMechanismDefinition`.
- **WildFly: MP-JWT is itself a Jakarta Security mechanism**, so the "one mechanism per application" limit that ADR
  0001 avoids by not shipping a Crema mechanism hits the Runtime's MP-JWT instead. It works only with an
  application-supplied Jakarta Security 4.0 (EE 11) `HttpAuthenticationMechanismHandler`.
- Tokens that fail validation get a Runtime 401 without `WWW-Authenticate` on TomEE and WildFly, so Crema can't add
  `resource_metadata` or `error="invalid_token"` there. On Liberty the same requests arrive as anonymous.
- On TomEE Crema must not call `getUserPrincipal()`/`isUserInRole()` on a request without `Authorization` header, or
  must catch the `RuntimeException`, to produce its own 401.
- `McpApplication`'s `final` methods break on Liberty unless the subclass is `@Dependent`; Crema's extension could add
  the scope, or drop `final`.
- On TomEE the application's scanned providers apply to MCP traffic, contrary to design §3.
