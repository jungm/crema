package io.github.jungm.crema.it.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import io.github.jungm.crema.it.support.Findings;

/**
 * A protected MCP Server, an open MCP Server, a plain JAX-RS API and a servlet protected by Jakarta Security's
 * OpenID Connect mechanism in one WAR, with tokens from a fake Authorization Server in the test JVM (ADR 0001).
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class ProtectedMcpServerIT {

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
        return SecurityWar.create("security", AS.issuer());
    }

    @AfterAll
    static void stop() {
        AS.close();
    }

    private String endpoint() {
        return base.toString() + "mcp";
    }

    private String metadataUrl() {
        return endpoint() + "/.well-known/oauth-protected-resource";
    }

    private String challenge() {
        return "Bearer resource_metadata=\"" + metadataUrl() + "\"";
    }

    private String invalidToken() {
        return "Bearer error=\"invalid_token\", resource_metadata=\"" + metadataUrl() + "\"";
    }

    @Test
    void withoutTokenCremaChallenges() {
        HttpResponse<String> response = post("mcp", "tools/list", "", null, null);
        record("no token", response);
        assertEquals(401, response.statusCode(), response::body);
        assertEquals(List.of(challenge()), response.headers().allValues("WWW-Authenticate"));
        assertEquals("", response.body());
    }

    @Test
    void everyMethodNeedsAToken() {
        assertEquals(401, post("mcp", "server/discover", "", null, null).statusCode());
        HttpResponse<String> get = send(HttpRequest.newBuilder(URI.create(endpoint())).GET(), null);
        assertEquals(401, get.statusCode());
        assertEquals(List.of(challenge()), get.headers().allValues("WWW-Authenticate"));
    }

    @Test
    void invalidTokensAreRejectedByCrema() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("expired", AS.token(endpoint(), c -> c.expirationTime(Date.from(Instant.now()
                .minusSeconds(3600)))));
        tokens.put("not yet valid", AS.token(endpoint(), c -> c.notBeforeTime(Date.from(Instant.now()
                .plusSeconds(3600)))));
        tokens.put("wrong issuer", AS.token(endpoint(), c -> c.issuer("https://evil.example.com/realm")));
        tokens.put("audience of the open MCP Server", AS.token(base + "open"));
        tokens.put("audience of the API", AS.token(base + "api"));
        tokens.put("unknown signing key", FakeAuthorizationServer.sign(FakeAuthorizationServer.rsa(AS.key
                .getKeyID()), JWSAlgorithm.RS256, AS.claims(endpoint(), c -> {
                })));
        tokens.put("alg none", new PlainJWT(AS.claims(endpoint(), c -> {
        })).serialize());
        tokens.put("HS256 with the public key", hmacWithPublicKey());
        tokens.put("malformed", "not-a-jwt");

        StringBuilder failures = new StringBuilder();
        tokens.forEach((kind, token) -> {
            HttpResponse<String> response = post("mcp", "tools/list", "", null, "Bearer " + token);
            record("invalid token: " + kind, response);
            if (response.statusCode() != 401
                    || !response.headers().allValues("WWW-Authenticate").equals(List.of(invalidToken()))
                    || !response.body().isEmpty()) {
                failures.append(kind).append(": ").append(response.statusCode()).append(' ')
                        .append(response.headers().allValues("WWW-Authenticate")).append(' ')
                        .append(abbreviate(response.body())).append('\n');
            }
        });
        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void validTokenAdmitsTheCallerWithItsRoles() {
        String token = AS.token(endpoint());
        HttpResponse<String> list = post("mcp", "tools/list", "", null, "Bearer " + token);
        record("valid token, tools/list", list);
        assertEquals(200, list.statusCode(), list::body);
        assertEquals(Set.of("everyone", "users", "whoami"), names(list, "tools"));
        assertEquals("private", result(list).get("cacheScope"));

        HttpResponse<String> whoami = callTool("mcp", "whoami", "bearer " + token);
        assertEquals(200, whoami.statusCode(), whoami::body);
        assertEquals("alice alice@example.com true", text(whoami));

        HttpResponse<String> forbidden = callTool("mcp", "admins", "Bearer " + token);
        record("valid token without role", forbidden);
        assertEquals(403, forbidden.statusCode(), forbidden::body);
        assertEquals(List.of("Bearer error=\"insufficient_scope\", resource_metadata=\"" + metadataUrl() + "\""),
                forbidden.headers().allValues("WWW-Authenticate"));

        String admin = AS.token(endpoint(), c -> c.claim("groups", List.of("admin")));
        assertEquals(Set.of("everyone", "admins"), names(post("mcp", "tools/list", "", null,
                "Bearer " + admin), "tools"));
        assertEquals("admins", text(callTool("mcp", "admins", "Bearer " + admin)));
        assertEquals(403, callTool("mcp", "users", "Bearer " + admin).statusCode());
    }

    @Test
    void rotatedKeysAreFetched() {
        RSAKey rotated = FakeAuthorizationServer.rsa("it-rotated");
        assertEquals(200, post("mcp", "tools/list", "", null, "Bearer " + AS.token(endpoint())).statusCode());
        AS.publish(rotated, AS.key);
        HttpResponse<String> response = post("mcp", "tools/list", "", null, "Bearer "
                + FakeAuthorizationServer.sign(rotated, JWSAlgorithm.RS256, AS.claims(endpoint(), c -> {
                })));
        record("token signed with a rotated key", response);
        assertEquals(200, response.statusCode(), response::body);
    }

    @Test
    void protectedResourceMetadataIsServedToAnyone() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(metadataUrl())).GET()
                .header("Origin", "https://evil.example.com"), null);
        record("metadata", response);
        assertEquals(200, response.statusCode(), response::body);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        Map<String, Object> metadata = json(response.body());
        assertEquals(Map.of("resource", endpoint(), "authorization_servers", List.of(AS.issuer()),
                "bearer_methods_supported", List.of("header")), metadata);
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "open/.well-known/oauth-protected-resource"))
                .GET(), null).statusCode());
    }

    @Test
    void openMcpServerUsesTheRuntimeCaller() {
        HttpResponse<String> list = post("open", "tools/list", "", null, null);
        record("open server, anonymous tools/list", list);
        assertEquals(200, list.statusCode(), list::body);
        assertEquals(Set.of("free"), names(list, "tools"));
        assertEquals("anonymous", text(callTool("open", "free", null)));
        HttpResponse<String> forbidden = callTool("open", "admin", null);
        assertEquals(403, forbidden.statusCode(), forbidden::body);
        assertEquals(List.of(), forbidden.headers().allValues("WWW-Authenticate"));

        HttpResponse<String> withToken = callTool("open", "free", "Bearer " + AS.token(base + "open"));
        record("open server with a bearer token", withToken);
        assertEquals("anonymous", text(withToken));
    }

    @Test
    void uiStillRedirectsToLogin() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "ui")).GET(), null);
        String location = response.headers().firstValue("Location").orElse("");
        FINDINGS.record("/ui without login", response.statusCode() + " Location: " + abbreviate(location));
        assertEquals(302, response.statusCode(), response::body);
        assertTrue(location.startsWith(AS.authorizationEndpoint()), location);
    }

    @Test
    void apiIsUntouched() {
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(URI.create(base + "api/hello")).GET(), null);
        HttpResponse<String> withToken = send(HttpRequest.newBuilder(URI.create(base + "api/hello")).GET(),
                "Bearer " + AS.token(endpoint()));
        FINDINGS.record("/api", "anonymous " + anonymous.statusCode() + " '" + anonymous.body().strip()
                + "', with MCP token " + withToken.statusCode() + " '" + withToken.body().strip() + "'");
        assertEquals(200, anonymous.statusCode(), anonymous::body);
        assertEquals("hello anonymous", anonymous.body().strip());
        assertEquals(200, withToken.statusCode(), withToken::body);
        assertEquals("hello anonymous", withToken.body().strip(), "the Runtime doesn't see token callers");
    }

    private String hmacWithPublicKey() {
        try {
            return FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(AS.key.getKeyID())
                    .build(), new MACSigner(AS.key.toRSAPublicKey().getEncoded()), AS.claims(endpoint(), c -> {
                    }));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> callTool(String server, String tool, String authorization) {
        return post(server, "tools/call", ",\"name\":\"" + tool + "\"", tool, authorization);
    }

    private HttpResponse<String> post(String server, String method, String params, String name,
            String authorization) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"crema-it\",\"version\":\"1\"}}" + params
                + "}}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + server))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", method);
        if (name != null) {
            request.header("Mcp-Name", name);
        }
        return send(request, authorization);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String authorization) {
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> json(String body) {
        try {
            return JSONObjectUtils.parse(body);
        } catch (ParseException e) {
            throw new AssertionError("Not JSON: " + body, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(HttpResponse<String> response) {
        Map<String, Object> message = json(response.body());
        assertNotNull(message.get("result"), response::body);
        return (Map<String, Object>) message.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> names(HttpResponse<String> response, String key) {
        Set<String> names = new TreeSet<>();
        for (Object item : (List<Object>) result(response).get(key)) {
            names.add((String) ((Map<String, Object>) item).get("name"));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static String text(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response::body);
        Map<String, Object> result = result(response);
        assertFalse(Boolean.TRUE.equals(result.get("isError")), response::body);
        return (String) ((Map<String, Object>) ((List<Object>) result.get("content")).get(0)).get("text");
    }

    private static void record(String item, HttpResponse<String> response) {
        FINDINGS.record(item, response.statusCode() + " WWW-Authenticate: "
                + response.headers().allValues("WWW-Authenticate") + " body: " + abbreviate(response.body()));
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "..." : oneLine;
    }
}
