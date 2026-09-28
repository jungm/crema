package io.github.jungm.crema.it.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.PlainJWT;

import io.github.jungm.crema.it.mcp.Exchange;
import io.github.jungm.crema.it.mcp.McpClient;
import io.github.jungm.crema.it.support.Findings;
import io.github.jungm.crema.testkit.FakeAuthorizationServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * A protected MCP Server, a {@code @DenyAll} one, an open one, a plain JAX-RS API and a servlet protected by Jakarta
 * Security's OpenID Connect mechanism in one WAR, with tokens from a fake Authorization Server in the test JVM (ADR
 * 0001). MCP traffic goes through {@link McpClient}, so every MCP message is validated against the MCP JSON Schema.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class ProtectedMcpServerIT {

    /**
     * The protected MCP Server's public URL, as behind a reverse proxy: tokens are issued for it, and it differs
     * from the URL the tests send requests to.
     */
    private static final String RESOURCE = "https://mcp.example.test/security/mcp";
    private static final String DENIED_RESOURCE = "https://mcp.example.test/security/denied";

    private static final FakeAuthorizationServer AS = FakeAuthorizationServer.start();
    private static final Findings FINDINGS = Findings.of(ProtectedMcpServerIT.class);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @ArquillianResource
    private URL base;

    @Deployment(testable = false)
    static WebArchive deployment() {
        return SecurityWar.create("security", AS.issuer(), RESOURCE, DENIED_RESOURCE);
    }

    @AfterAll
    static void stop() {
        AS.close();
    }

    private McpClient mcp() {
        return McpClient.at(base, "mcp");
    }

    private static String metadataUrl(String resource) {
        return resource + "/.well-known/oauth-protected-resource";
    }

    private static String challenge() {
        return "Bearer resource_metadata=\"" + metadataUrl(RESOURCE) + "\"";
    }

    private static String invalidToken() {
        return "Bearer error=\"invalid_token\", resource_metadata=\"" + metadataUrl(RESOURCE) + "\"";
    }

    private static String insufficientScope(String resource) {
        return "Bearer error=\"insufficient_scope\", resource_metadata=\"" + metadataUrl(resource) + "\"";
    }

    @Test
    void withoutTokenCremaChallenges() {
        Exchange response = mcp().post("tools/list").send();
        record("no token", response);
        assertRejected(401, challenge(), response);
    }

    @Test
    void everyMethodNeedsAToken() {
        assertRejected(401, challenge(), mcp().post("server/discover").send());
        HttpResponse<String> get = mcp().send("GET", Map.of());
        assertEquals(401, get.statusCode());
        assertEquals(List.of(challenge()), get.headers().allValues("WWW-Authenticate"));
    }

    @Test
    void authorizationMustBeExactlyOneBearerToken() {
        String token = AS.token(RESOURCE);
        Exchange basic = list("mcp", "Basic YWxpY2U6c2VjcmV0");
        record("Basic scheme", basic);
        assertRejected(401, challenge(), basic);

        Exchange empty = list("mcp", "");
        record("empty Authorization", empty);
        assertRejected(401, challenge(), empty);

        Exchange noToken = list("mcp", "Bearer");
        record("Bearer without token", noToken);
        assertRejected(401, invalidToken(), noToken);

        Exchange twoTokens = list("mcp", "Bearer " + token + " " + token);
        record("two tokens in one Authorization", twoTokens);
        assertRejected(401, invalidToken(), twoTokens);

        Exchange repeated = mcp().post("tools/list").header("Authorization", "Bearer " + token)
                .addHeader("Authorization", "Bearer " + token).send();
        record("Authorization twice", repeated);
        assertRejected(401, invalidToken(), repeated);
    }

    @Test
    void invalidTokensAreRejectedByCrema() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("expired", AS.token(RESOURCE, c -> c.expirationTime(Date.from(Instant.now()
                .minusSeconds(3600)))));
        tokens.put("not yet valid", AS.token(RESOURCE, c -> c.notBeforeTime(Date.from(Instant.now()
                .plusSeconds(3600)))));
        tokens.put("wrong issuer", AS.token(RESOURCE, c -> c.issuer("https://evil.example.com/realm")));
        tokens.put("audience of the request URL", AS.token(mcp().endpoint().toString()));
        tokens.put("audience of the open MCP Server", AS.token(base + "open"));
        tokens.put("audience of the API", AS.token(base + "api"));
        tokens.put("audience of the @DenyAll MCP Server", AS.token(DENIED_RESOURCE));
        tokens.put("without principal claim", AS.token(RESOURCE, c -> c.subject(null)));
        tokens.put("unknown signing key", FakeAuthorizationServer.sign(FakeAuthorizationServer.rsa(
                AS.rsaKey().getKeyID()), JWSAlgorithm.RS256, AS.claims(RESOURCE, c -> {
                })));
        tokens.put("alg none", new PlainJWT(AS.claims(RESOURCE, c -> {
        })).serialize());
        tokens.put("HS256 with the public key", hmacWithPublicKey());
        tokens.put("malformed", "not-a-jwt");

        StringBuilder failures = new StringBuilder();
        tokens.forEach((kind, token) -> {
            Exchange response = list("mcp", "Bearer " + token);
            record("invalid token: " + kind, response);
            if (response.status() != 401
                    || !response.response().headers().allValues("WWW-Authenticate").equals(List.of(invalidToken()))
                    || !response.body().isEmpty()) {
                failures.append(kind).append(": ").append(response.status()).append(' ')
                        .append(response.response().headers().allValues("WWW-Authenticate")).append(' ')
                        .append(abbreviate(response.body())).append('\n');
            }
        });
        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void validTokenAdmitsTheCallerWithItsRoles() {
        String token = AS.token(RESOURCE);
        Exchange list = list("mcp", "Bearer " + token);
        record("valid token, tools/list", list);
        assertEquals(Set.of("everyone", "users", "whoami"), names(list.result(), "tools"));
        assertEquals("private", list.result().getString("cacheScope"));

        assertEquals("alice alice@example.com true", text(tool("mcp", "whoami", "bearer " + token)));

        Exchange forbidden = tool("mcp", "admins", "Bearer " + token);
        record("valid token without role", forbidden);
        assertRejected(403, insufficientScope(RESOURCE), forbidden);

        String admin = AS.token(RESOURCE, c -> c.claim("groups", List.of("admin")));
        assertEquals(Set.of("everyone", "admins", "adminProgress"), names(list("mcp", "Bearer " + admin).result(),
                "tools"));
        assertEquals("admins", text(tool("mcp", "admins", "Bearer " + admin)));
        assertEquals(403, tool("mcp", "users", "Bearer " + admin).status());
    }

    @Test
    void everyKindOfHiddenFeatureIsForbidden() {
        String user = "Bearer " + AS.token(RESOURCE);
        Map<String, Exchange> exchanges = new LinkedHashMap<>();
        exchanges.put("resources/read", mcp().post("resources/read")
                .params(Json.createObjectBuilder().add("uri", "secure://admin").build())
                .header("Authorization", user).send());
        exchanges.put("prompts/get", mcp().post("prompts/get")
                .params(Json.createObjectBuilder().add("name", "adminPrompt")
                        .add("arguments", Json.createObjectBuilder().add("topic", "x")).build())
                .header("Authorization", user).send());
        exchanges.put("completion/complete", mcp().post("completion/complete")
                .params(Json.createObjectBuilder()
                        .add("ref", Json.createObjectBuilder().add("type", "ref/prompt").add("name", "adminPrompt"))
                        .add("argument", Json.createObjectBuilder().add("name", "topic").add("value", "x"))
                        .build())
                .header("Authorization", user).send());
        exchanges.forEach((method, exchange) -> {
            record("hidden Feature, " + method, exchange);
            assertRejected(403, insufficientScope(RESOURCE), exchange);
        });

        String admin = "Bearer " + AS.token(RESOURCE, c -> c.claim("groups", List.of("admin")));
        assertEquals("admin resource", mcp().post("resources/read")
                .params(Json.createObjectBuilder().add("uri", "secure://admin").build())
                .header("Authorization", admin).send().result().getJsonArray("contents").getJsonObject(0)
                .getString("text"));
    }

    @Test
    void hiddenProgressToolIsForbiddenInsteadOfStreamed() {
        Exchange forbidden = mcp().post("tools/call")
                .params(Json.createObjectBuilder().add("name", "adminProgress").build())
                .meta("progressToken", "p").header("Authorization", "Bearer " + AS.token(RESOURCE)).send();
        record("hidden Tool with Progress and a progressToken", forbidden);
        assertFalse(forbidden.isEventStream(), forbidden::describe);
        assertRejected(403, insufficientScope(RESOURCE), forbidden);

        Exchange streamed = mcp().post("tools/call")
                .params(Json.createObjectBuilder().add("name", "adminProgress").build())
                .meta("progressToken", "p")
                .header("Authorization", "Bearer " + AS.token(RESOURCE, c -> c.claim("groups", List.of("admin"))))
                .send();
        assertTrue(streamed.isEventStream(), streamed::describe);
        assertEquals(2, streamed.messages().size());
        assertEquals("progress", text(streamed));
    }

    @Test
    void denyAllMcpApplicationDeniesWhatItsMethodsDontPermit() {
        McpClient denied = McpClient.at(base, "denied");
        Exchange anonymous = denied.post("tools/list").send();
        assertEquals(401, anonymous.status(), anonymous::describe);
        assertEquals("Bearer resource_metadata=\"" + metadataUrl(DENIED_RESOURCE) + "\"",
                anonymous.header("WWW-Authenticate").orElse(null));

        String token = "Bearer " + AS.token(DENIED_RESOURCE, c -> c.claim("groups", List.of("user", "admin")));
        Exchange list = denied.post("tools/list").header("Authorization", token).send();
        record("@DenyAll MCP Server, tools/list", list);
        assertEquals(Set.of("permitted"), names(list.result(), "tools"));
        assertEquals("permitted", text(denied.post("tools/call")
                .params(Json.createObjectBuilder().add("name", "permitted").build())
                .header("Authorization", token).send()));
        Exchange forbidden = denied.post("tools/call").params(Json.createObjectBuilder().add("name", "denied").build())
                .header("Authorization", token).send();
        record("@DenyAll MCP Server, denied Tool", forbidden);
        assertRejected(403, insufficientScope(DENIED_RESOURCE), forbidden);

        assertEquals(401, denied.post("tools/list").header("Authorization", "Bearer " + AS.token(RESOURCE)).send()
                .status(), "a token for the other protected MCP Server");
    }

    @Test
    void rotatedKeysAreFetched() {
        RSAKey rotated = FakeAuthorizationServer.rsa("it-rotated");
        assertEquals(200, list("mcp", "Bearer " + AS.token(RESOURCE)).status());
        AS.publish(rotated, AS.rsaKey(), AS.ecKey());
        Exchange response = list("mcp", "Bearer " + FakeAuthorizationServer.sign(rotated, JWSAlgorithm.RS256,
                AS.claims(RESOURCE, c -> {
                })));
        record("token signed with a rotated key", response);
        response.result();
    }

    @Test
    void protectedResourceMetadataIsServedToAnyone() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(mcp().endpoint()
                + "/.well-known/oauth-protected-resource")).GET()
                .header("Origin", "https://evil.example.com"));
        FINDINGS.record("metadata", response.statusCode() + " " + abbreviate(response.body()));
        assertEquals(200, response.statusCode(), response::body);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(Map.of("resource", RESOURCE, "authorization_servers", List.of(AS.issuer()),
                "bearer_methods_supported", List.of("header")), json(response.body()));
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "open/.well-known/oauth-protected-resource"))
                .GET()).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(URI.create(mcp().endpoint()
                + "/.well-known/oauth-protected-resource")).POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(mcp().endpoint() + "/other")).GET()).statusCode());
    }

    @Test
    void openMcpServerUsesTheRuntimeCaller() {
        McpClient open = McpClient.at(base, "open");
        Exchange list = open.post("tools/list").send();
        record("open server, anonymous tools/list", list);
        assertEquals(Set.of("free"), names(list.result(), "tools"));
        assertEquals("private", list.result().getString("cacheScope"), "the admin Tool is restricted to a role");
        assertEquals("anonymous", text(open.callTool("free", null)));
        Exchange forbidden = open.callTool("admin", null);
        assertEquals(403, forbidden.status(), forbidden::describe);
        assertEquals(List.of(), forbidden.response().headers().allValues("WWW-Authenticate"));

        Exchange withToken = open.post("tools/call").params(Json.createObjectBuilder().add("name", "free").build())
                .header("Authorization", "Bearer " + AS.token(base + "open")).send();
        record("open server with a bearer token", withToken);
        assertEquals("anonymous", text(withToken));
    }

    @Test
    void uiStillRedirectsToLogin() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "ui")).GET());
        String location = response.headers().firstValue("Location").orElse("");
        FINDINGS.record("/ui without login", response.statusCode() + " Location: " + abbreviate(location));
        assertEquals(302, response.statusCode(), response::body);
        assertTrue(location.startsWith(AS.authorizationEndpoint()), location);
    }

    @Test
    void apiIsUntouched() {
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(URI.create(base + "api/hello")).GET());
        HttpResponse<String> withToken = send(HttpRequest.newBuilder(URI.create(base + "api/hello")).GET()
                .header("Authorization", "Bearer " + AS.token(RESOURCE)));
        FINDINGS.record("/api", "anonymous " + anonymous.statusCode() + " '" + anonymous.body().strip()
                + "', with MCP token " + withToken.statusCode() + " '" + withToken.body().strip() + "'");
        assertEquals(200, anonymous.statusCode(), anonymous::body);
        assertEquals("hello anonymous", anonymous.body().strip());
        assertEquals(200, withToken.statusCode(), withToken::body);
        assertEquals("hello anonymous", withToken.body().strip(), "the Runtime doesn't see token callers");
    }

    private String hmacWithPublicKey() {
        try {
            return FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .keyID(AS.rsaKey().getKeyID()).build(), new MACSigner(AS.rsaKey().toRSAPublicKey().getEncoded()),
                    AS.claims(RESOURCE, c -> {
                    }));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private Exchange list(String server, String authorization) {
        return McpClient.at(base, server).post("tools/list").header("Authorization", authorization).send();
    }

    private Exchange tool(String server, String tool, String authorization) {
        return McpClient.at(base, server).post("tools/call")
                .params(Json.createObjectBuilder().add("name", tool).build())
                .header("Authorization", authorization).send();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Asserts a rejection by Crema: the status, exactly this challenge, and an empty body.
     */
    private static void assertRejected(int status, String challenge, Exchange exchange) {
        assertEquals(status, exchange.status(), exchange::describe);
        assertEquals(List.of(challenge), exchange.response().headers().allValues("WWW-Authenticate"),
                exchange::describe);
        assertEquals("", exchange.body(), exchange::describe);
    }

    private static Map<String, Object> json(String body) {
        try {
            return JSONObjectUtils.parse(body);
        } catch (ParseException e) {
            throw new AssertionError("Not JSON: " + body, e);
        }
    }

    private static Set<String> names(JsonObject result, String key) {
        Set<String> names = new TreeSet<>();
        result.getJsonArray(key).forEach(item -> names.add(item.asJsonObject().getString("name")));
        return names;
    }

    private static String text(Exchange exchange) {
        JsonObject result = exchange.result();
        assertFalse(result.getBoolean("isError", false), exchange::describe);
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    private static void record(String item, Exchange exchange) {
        FINDINGS.record(item, exchange.status() + " WWW-Authenticate: "
                + exchange.response().headers().allValues("WWW-Authenticate") + " body: "
                + abbreviate(exchange.body()));
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "..." : oneLine;
    }
}
