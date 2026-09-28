package io.github.jungm.crema.it.security;

import io.github.jungm.crema.it.security.app.AppResponseFilter;
import io.github.jungm.crema.it.support.FakeOidcProvider;
import io.github.jungm.crema.it.support.Findings;
import io.github.jungm.crema.it.support.Jwts;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP-JWT on an MCP-style JAX-RS application, a second MP-JWT protected JAX-RS application, and a servlet protected
 * by Jakarta Security's OpenID Connect mechanism, all in one WAR (see ADR 0001).
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class SecurityCoexistenceIT {

    private static final FakeOidcProvider OIDC = FakeOidcProvider.start();
    private static final Findings FINDINGS = Findings.of(SecurityCoexistenceIT.class);

    /**
     * Whether the Runtime authenticates bearer tokens with MP-JWT while the WAR also has a Jakarta Security
     * mechanism. False on Liberty, where the mechanism handles every request and the MP-JWT trust association
     * interceptor never runs; the tests then pin down that behaviour instead.
     */
    private static final boolean MP_JWT_WITH_MECHANISM =
            Boolean.parseBoolean(System.getProperty("crema.it.mpjwt-with-mechanism", "true"));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @ArquillianResource
    private URL base;

    @Deployment(testable = false)
    static WebArchive deployment() {
        return CoexistenceWar.create("security-coexistence", CoexistenceWar.loginConfig(), OIDC.issuer());
    }

    @AfterAll
    static void stopProvider() {
        OIDC.close();
    }

    @Test
    void deploymentSucceedsWithBothMechanisms() {
        assertNotNull(base, "deployment URL");
        HttpResponse<String> response = get("mcp/whoami", null);
        record("1 deployment", "ok, /mcp/whoami -> " + response.statusCode());
        assertEquals(200, response.statusCode(), response::body);
    }

    @Test
    void mcpWithoutTokenReachesResourceAnonymously() {
        HttpResponse<String> response = get("mcp/whoami", null);
        Map<String, String> caller = parse(response.body());
        record("2 /mcp no token", response.statusCode() + " name=" + caller.get("name") + error(caller));
        assertEquals(200, response.statusCode(), () -> "Runtime answered instead of the resource: " + response.body());
        assertEquals("anonymous", caller.get("name"), response::body);
        assertEquals("false", caller.get("jwt"), response::body);
    }

    @Test
    void mcpWithValidTokenSeesJsonWebToken() {
        HttpResponse<String> response = get("mcp/whoami", Jwts.token().build());
        Map<String, String> caller = parse(response.body());
        record("3 /mcp valid token", response.statusCode() + " name=" + caller.get("name") + " jwt=" + caller.get("jwt")
                + " aud=" + caller.get("aud") + " inRoleUser=" + caller.get("inRoleUser")
                + " principal=" + caller.get("principalClass"));
        assertEquals(200, response.statusCode(), response::body);
        if (!MP_JWT_WITH_MECHANISM) {
            assertEquals("anonymous", caller.get("name"), () -> "MP-JWT now authenticates here: " + response.body());
            return;
        }
        assertEquals("alice", caller.get("name"), response::body);
        assertEquals("true", caller.get("jwt"), response::body);
        assertEquals(Jwts.MCP_AUDIENCE, caller.get("aud"), response::body);
        assertEquals("user", caller.get("groups"), response::body);
        assertEquals("true", caller.get("inRoleUser"), response::body);
        assertEquals("alice", caller.get("servletName"), response::body);
    }

    @Test
    void mcpWithInvalidTokenIsNeverAuthenticated() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("expired", Jwts.token().expiresAt(Instant.now().minusSeconds(3600)).build());
        tokens.put("wrong issuer", Jwts.token().issuer("https://evil.crema.test").build());
        tokens.put("wrong signature", Jwts.token().signedWith(Jwts.UNTRUSTED.getPrivate()).build());
        tokens.put("unlisted aud", Jwts.token().audience("https://other.crema.test").build());
        tokens.put("malformed", "not-a-jwt");

        StringBuilder failures = new StringBuilder();
        tokens.forEach((kind, token) -> {
            HttpResponse<String> response = get("mcp/whoami", token);
            String outcome;
            if (response.statusCode() == 200) {
                Map<String, String> caller = parse(response.body());
                outcome = "200 name=" + caller.get("name") + error(caller);
                if (!"anonymous".equals(caller.get("name"))) {
                    failures.append(kind).append(" authenticated as ").append(caller.get("name")).append('\n');
                }
            } else {
                outcome = response.statusCode() + " (Runtime)"
                        + response.headers().firstValue("WWW-Authenticate").map(h -> " WWW-Authenticate: " + h).orElse("")
                        + " body: " + abbreviate(response.body());
            }
            record("4 /mcp " + kind, outcome);
        });
        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void apiIsMpJwtProtected() {
        HttpResponse<String> anonymous = get("api/hello", null);
        HttpResponse<String> valid = get("api/hello", Jwts.token().audience(Jwts.API_AUDIENCE).build());
        HttpResponse<String> noRole = get("api/hello", Jwts.token().audience(Jwts.API_AUDIENCE).groups("other").build());
        HttpResponse<String> expired = get("api/hello",
                Jwts.token().audience(Jwts.API_AUDIENCE).expiresAt(Instant.now().minusSeconds(3600)).build());
        record("5 /api", "none=" + anonymous.statusCode() + " valid=" + valid.statusCode()
                + " noRole=" + noRole.statusCode() + " expired=" + expired.statusCode());
        // RESTEasy's @RolesAllowed filter answers 403 for anonymous callers; the others answer 401.
        assertTrue(anonymous.statusCode() == 401 || anonymous.statusCode() == 403,
                () -> anonymous.statusCode() + " " + anonymous.body());
        if (!MP_JWT_WITH_MECHANISM) {
            assertEquals(403, valid.statusCode(), () -> "MP-JWT now authenticates here: " + valid.body());
            return;
        }
        assertEquals(200, valid.statusCode(), valid::body);
        assertEquals("hello alice", valid.body().strip());
        assertEquals(403, noRole.statusCode(), noRole::body);
        assertTrue(expired.statusCode() == 401 || expired.statusCode() == 403,
                () -> expired.statusCode() + " " + expired.body());
    }

    @Test
    void uiRedirectsToOidcProvider() {
        HttpResponse<String> response = get("ui", null);
        String location = response.headers().firstValue("Location").orElse("");
        record("6 /ui", response.statusCode() + " Location=" + location.replaceAll("\\?.*", "?..."));
        assertTrue(response.statusCode() == 302 || response.statusCode() == 303,
                () -> response.statusCode() + " " + response.body() + " provider requests: " + OIDC.requests());
        assertTrue(location.startsWith(OIDC.authorizationEndpoint()), location);
        assertTrue(location.contains("client_id=crema-it"), location);
        assertFalse(location.contains("client_secret"), location);
    }

    @Test
    void recordWhetherScannedProvidersReachMcp() {
        HttpResponse<String> mcp = get("mcp/whoami", null);
        HttpResponse<String> api = get("api/hello", Jwts.token().audience(Jwts.API_AUDIENCE).build());
        record("7 scanned @Provider", "/mcp=" + mcp.headers().firstValue(AppResponseFilter.HEADER).orElse("not applied")
                + " /api=" + api.headers().firstValue(AppResponseFilter.HEADER).orElse("not applied"));
    }

    private HttpResponse<String> get(String path, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String body) {
        String text = body.replaceAll("(?s)<style.*?</style>", "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private static String error(Map<String, String> caller) {
        String error = caller.get("principalError");
        return error == null ? "" : " getUserPrincipal() threw " + error;
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        body.lines().forEach(line -> {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        });
        return values;
    }

    private static void record(String item, String outcome) {
        FINDINGS.record(item, outcome);
    }
}
