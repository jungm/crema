package io.github.jungm.crema.internal.security;

import static io.github.jungm.crema.internal.security.Fixture.ENDPOINT;
import static io.github.jungm.crema.internal.security.Fixture.OTHER_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import io.github.jungm.crema.internal.security.Fixture.Exchange;
import io.github.jungm.crema.internal.security.Fixture.RequestCaller;
import io.github.jungm.crema.testkit.FakeAuthorizationServer;

/**
 * Bearer token validation on a protected MCP Server, against a fake Authorization Server.
 */
class BearerTokenTest {

    private static final String METADATA = ENDPOINT + "/.well-known/oauth-protected-resource";
    private static final String CHALLENGE = "Bearer resource_metadata=\"" + METADATA + "\"";
    private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\", resource_metadata=\"" + METADATA
            + "\"";

    private final FakeAuthorizationServer as = FakeAuthorizationServer.start();
    private Fixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.policy.close();
        }
        as.close();
    }

    private Fixture fixture(boolean discover) {
        fixture = new Fixture(Fixture.protection(as, "default", ENDPOINT, "groups", discover),
                Fixture.protection(as, "other", OTHER_ENDPOINT, "groups", discover));
        return fixture;
    }

    private Exchange whoami(String authorization) {
        return fixture.callTool(fixture.protectedServer, RequestCaller.withAuthorization(authorization),
                "whoami");
    }

    private Exchange whoamiWithToken(String token) {
        return whoami("Bearer " + token);
    }

    private void assertInvalid(String token) {
        Exchange exchange = whoamiWithToken(token);
        assertEquals(401, exchange.status());
        assertEquals(INVALID_TOKEN, exchange.challenge());
        assertNull(exchange.message());
    }

    @Test
    void validTokenAdmitsTheCallerNamedBySub() {
        fixture(false);
        Exchange exchange = whoamiWithToken(as.token(ENDPOINT));
        assertEquals(200, exchange.status(), String.valueOf(exchange.message()));
        assertEquals("alice alice@example.com true", exchange.text());
    }

    @Test
    void missingAuthorizationGetsAChallenge() {
        fixture(false);
        Exchange exchange = fixture.callTool(fixture.protectedServer, RequestCaller.anonymous(), "everyone");
        assertEquals(401, exchange.status());
        assertEquals(CHALLENGE, exchange.challenge());
        assertNull(exchange.message());
    }

    @Test
    void everyMethodNeedsAToken() {
        fixture(false);
        for (String method : List.of("server/discover", "tools/list", "prompts/list")) {
            assertEquals(401, fixture.call(fixture.protectedServer, RequestCaller.anonymous(), method, "",
                    null).status(), method);
        }
    }

    @Test
    void otherSchemesGetAChallenge() {
        fixture(false);
        Exchange exchange = whoami("Basic YWxpY2U6c2VjcmV0");
        assertEquals(401, exchange.status());
        assertEquals(CHALLENGE, exchange.challenge());
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        fixture(false);
        String token = as.token(ENDPOINT);
        assertEquals(200, whoami("bearer " + token).status());
        assertEquals(200, whoami("BEARER " + token).status());
        assertEquals(200, whoami("  Bearer   " + token + " ").status());
    }

    @Test
    void emptyOrRepeatedAuthorizationIsInvalid() {
        fixture(false);
        assertEquals(INVALID_TOKEN, whoami("Bearer").challenge());
        assertEquals(INVALID_TOKEN, whoami("Bearer   ").challenge());
        Exchange twice = fixture.callTool(fixture.protectedServer,
                RequestCaller.withAuthorization("Bearer " + as.token(ENDPOINT), "Bearer x"), "whoami");
        assertEquals(401, twice.status());
        assertEquals(INVALID_TOKEN, twice.challenge());
    }

    @Test
    void tokensOutsideTheB64tokenSyntaxAreInvalid() {
        fixture(false);
        String token = as.token(ENDPOINT);
        assertEquals(INVALID_TOKEN, whoami("Bearer " + token + " " + token).challenge(), "two tokens");
        assertEquals(INVALID_TOKEN, whoami("Bearer " + token + "\tx").challenge(), "a tab inside the token");
        assertEquals(200, whoami("Bearer " + token + "\t").status(), "surrounding whitespace isn't part of it");
        assertEquals(INVALID_TOKEN, whoami("Bearer " + token + ", Bearer " + token).challenge(),
                "two Authorization headers joined by the Runtime");
        assertEquals(INVALID_TOKEN, whoami("Bearer " + token.replace('.', '!')).challenge());
        assertEquals(INVALID_TOKEN, whoami("Bearer \"" + token + "\"").challenge());
    }

    @Test
    void malformedTokenIsInvalid() {
        fixture(false);
        assertInvalid("not-a-jwt");
        assertInvalid("a.b.c");
        assertInvalid(as.token(ENDPOINT) + "x");
    }

    @Test
    void expiredTokenIsInvalid() {
        fixture(false);
        assertInvalid(as.token(ENDPOINT, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
    }

    @Test
    void expiryWithinTheClockSkewIsTolerated() {
        fixture(false);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT,
                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(30))))).status());
    }

    @Test
    void tokenWithoutExpiryIsInvalid() {
        fixture(false);
        assertInvalid(as.token(ENDPOINT, c -> c.expirationTime(null)));
    }

    @Test
    void notBeforeInTheFutureIsInvalid() {
        fixture(false);
        assertInvalid(as.token(ENDPOINT, c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120)))));
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT,
                c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(30))))).status());
    }

    @Test
    void wrongIssuerIsInvalid() {
        fixture(false);
        assertInvalid(as.token(ENDPOINT, c -> c.issuer("https://evil.example.com/realm")));
        assertInvalid(as.token(ENDPOINT, c -> c.issuer(as.issuer() + "/")));
        assertInvalid(as.token(ENDPOINT, c -> c.issuer(null)));
    }

    @Test
    void wrongAudienceIsInvalid() {
        fixture(false);
        assertInvalid(as.token("https://api.example.com"));
        assertInvalid(as.token(ENDPOINT + "/"));
        assertInvalid(as.token(ENDPOINT, c -> c.audience((String) null)));
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT, c -> c.audience(List.of("https://api.example.com",
                ENDPOINT)))).status());
    }

    @Test
    void tokenForAnotherMcpServerOfTheApplicationIsInvalid() {
        fixture(false);
        String otherToken = as.token(OTHER_ENDPOINT);
        assertInvalid(otherToken);
        assertEquals(200, fixture.callTool(fixture.otherServer, RequestCaller.bearer(otherToken),
                "other").status());
        Exchange mine = fixture.callTool(fixture.otherServer, RequestCaller.bearer(as.token(ENDPOINT)), "other");
        assertEquals(401, mine.status());
        assertEquals("Bearer error=\"invalid_token\", resource_metadata=\"" + OTHER_ENDPOINT
                + "/.well-known/oauth-protected-resource\"", mine.challenge());
    }

    @Test
    void theConfiguredResourceIsTheAudienceAndLocatesTheMetadata() {
        String resource = "https://public.test/ctx/mcp/";
        fixture = new Fixture(Fixture.protection(as, "default", resource, "groups", false),
                Fixture.protection(as, "other", OTHER_ENDPOINT, "groups", false));
        assertEquals(200, whoamiWithToken(as.token(resource)).status());
        Exchange withoutSlash = whoamiWithToken(as.token("https://public.test/ctx/mcp"));
        assertEquals(401, withoutSlash.status());
        assertEquals("Bearer error=\"invalid_token\", resource_metadata=\"https://public.test/ctx/mcp"
                + "/.well-known/oauth-protected-resource\"", withoutSlash.challenge());
    }

    @Test
    void algNoneIsInvalid() {
        fixture(false);
        assertInvalid(new PlainJWT(as.claims(ENDPOINT, c -> {
        })).serialize());
    }

    @Test
    void hmacWithThePublicKeyAsSecretIsInvalid() throws JOSEException {
        fixture(false);
        byte[] publicKey = as.rsaKey().toRSAPublicKey().getEncoded();
        assertInvalid(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID(as.rsaKey().getKeyID()).build(), new MACSigner(publicKey), as.claims(ENDPOINT, c -> {
                })));
    }

    @Test
    void signatureByAnUnknownKeyWithAKnownKidIsInvalid() {
        fixture(false);
        RSAKey impostor = FakeAuthorizationServer.rsa(as.rsaKey().getKeyID());
        assertInvalid(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(impostor.getKeyID()).build(), FakeAuthorizationServer.rsaSigner(impostor),
                as.claims(ENDPOINT, c -> {
                })));
    }

    @Test
    void otherAsymmetricAlgorithmsAreAccepted() {
        fixture(false);
        JWTClaimsSet claims = as.claims(ENDPOINT, c -> {
        });
        assertEquals(200, whoamiWithToken(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(as.ecKey().getKeyID()).build(), FakeAuthorizationServer.ecSigner(as.ecKey()), claims)).status());
        assertEquals(200, whoamiWithToken(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.PS256)
                .keyID(as.rsaKey().getKeyID()).type(JOSEObjectType.JWT).build(),
                FakeAuthorizationServer.rsaSigner(as.rsaKey()), claims)).status());
    }

    @Test
    void onlyRsaAndEcdsaAlgorithmsAreAccepted() {
        assertEquals(java.util.Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384,
                JWSAlgorithm.ES512), TokenValidator.ALGORITHMS);
    }

    @Test
    void edDsaIsInvalid() {
        fixture(false);
        String payload = as.token(ENDPOINT).split("\\.")[1];
        String header = com.nimbusds.jose.util.Base64URL.encode("{\"alg\":\"EdDSA\",\"kid\":\"ed-1\"}").toString();
        assertInvalid(header + "." + payload + "." + com.nimbusds.jose.util.Base64URL.encode(new byte[64]));
    }

    @Test
    void typeMustBeAnAccessTokenOrJwt() {
        fixture(false);
        JWTClaimsSet claims = as.claims(ENDPOINT, c -> {
        });
        assertEquals(200, whoamiWithToken(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(as.rsaKey().getKeyID()).build(), FakeAuthorizationServer.rsaSigner(as.rsaKey()), claims)).status());
        assertInvalid(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(as.rsaKey().getKeyID()).type(new JOSEObjectType("secevent+jwt")).build(),
                FakeAuthorizationServer.rsaSigner(as.rsaKey()), claims));
    }

    @Test
    void missingPrincipalClaimIsInvalid() {
        fixture(false);
        assertInvalid(as.token(ENDPOINT, c -> c.subject(null)));
    }

    @Test
    void unknownKidRefreshesTheKeysSoRotationWorks() {
        fixture(false);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT)).status());
        int before = as.jwksRequests();
        RSAKey rotated = FakeAuthorizationServer.rsa("rsa-2");
        as.publish(rotated, as.rsaKey());
        String token = FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rotated.getKeyID()).build(), FakeAuthorizationServer.rsaSigner(rotated),
                as.claims(ENDPOINT, c -> {
                }));
        assertEquals(200, whoamiWithToken(token).status());
        assertEquals(before + 1, as.jwksRequests());
        assertEquals(200, whoamiWithToken(token).status());
        assertEquals(before + 1, as.jwksRequests(), "the rotated keys are cached");
    }

    /**
     * Waits for the key cache to expire for real: Nimbus' {@code JWKSourceBuilder} has no clock to inject (its
     * {@code JWKSetBasedJWKSource} reads {@code System.currentTimeMillis()}), so the tests use a short
     * {@link Fixture#FAST} cache lifetime instead.
     */
    @Test
    void cachedKeysOutliveAnOutageOfTheJwksEndpoint() throws InterruptedException {
        fixture(false);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT)).status());
        as.jwksDown(true);
        Thread.sleep(Fixture.FAST.cacheTtl().toMillis() + 300);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT)).status());
        assertTrue(as.jwksRequests() > 1, "the keys were retrieved again");
    }

    @Test
    void unavailableKeysMakeTokensInvalid() {
        as.jwksDown(true);
        fixture(false);
        assertInvalid(as.token(ENDPOINT));
    }

    @Test
    void keysAreFoundThroughOpenIdConnectDiscovery() {
        fixture(true);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT)).status());
    }

    @Test
    void keysAreFoundThroughRfc8414Metadata() {
        as.oidcDiscovery(false);
        fixture(true);
        assertEquals(200, whoamiWithToken(as.token(ENDPOINT)).status());
    }

    @Test
    void aStalledMetadataResponseTimesOutAndDiscoveryIsRetried() throws InterruptedException {
        as.stallMetadata(true);
        fixture(true);
        long start = System.nanoTime();
        assertInvalid(as.token(ENDPOINT));
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed < 15_000, "the read timeout bounds the metadata retrieval, but it took " + elapsed + " ms");
        assertTrue(as.metadataRequests() > 0);
        as.stallMetadata(false);
        int status = 0;
        for (long deadline = System.nanoTime() + 10_000_000_000L; status != 200 && System.nanoTime() < deadline;) {
            Thread.sleep(Fixture.FAST.rateLimit().toMillis() + 100);
            status = whoamiWithToken(as.token(ENDPOINT)).status();
        }
        assertEquals(200, status, "the metadata is read again once it responds");
    }

    @Test
    void redirectsOfTheJwkSetAreNotFollowed() {
        as.redirect(true);
        fixture(false);
        assertInvalid(as.token(ENDPOINT));
        assertEquals(0, as.movedRequests());
    }

    @Test
    void redirectsOfTheMetadataAreNotFollowed() {
        as.redirect(true);
        fixture(true);
        assertInvalid(as.token(ENDPOINT));
        assertEquals(0, as.movedRequests());
    }

    @Test
    void plainHttpJwksUriIsAcceptedOnlyForLoopbackIssuers() {
        assertTrue(IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("https://as.test/keys"),
                "https://as.test"));
        assertTrue(IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("http://127.0.0.1:8080/keys"),
                "http://localhost:8080/realm"));
        assertTrue(IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("http://localhost/keys"),
                "https://127.0.0.1/realm"));
        assertEquals(false, IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("http://localhost:8080/keys"),
                "https://as.test"));
        assertEquals(false, IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("http://as.test/keys"),
                "http://localhost"));
        assertEquals(false, IssuerJwkSetSource.isAcceptableJwksUri(java.net.URI.create("file:///etc/keys"),
                "http://localhost"));
    }

    @Test
    void metadataNamingAnotherIssuerIsRejected() {
        as.advertiseIssuer("https://evil.example.com/realm");
        fixture(true);
        assertInvalid(as.token(ENDPOINT));
        assertEquals(0, as.jwksRequests());
    }

    @Test
    void metadataUrlsFollowOidcAndRfc8414() {
        assertEquals(List.of("https://as.test/realms/x/.well-known/openid-configuration",
                "https://as.test/.well-known/oauth-authorization-server/realms/x"),
                IssuerJwkSetSource.metadataUrls("https://as.test/realms/x/").stream().map(Object::toString)
                        .toList());
        assertEquals(List.of("https://as.test/.well-known/openid-configuration",
                "https://as.test/.well-known/oauth-authorization-server"),
                IssuerJwkSetSource.metadataUrls("https://as.test").stream().map(Object::toString).toList());
    }

    @Test
    void claimsAreReadByDottedPath() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("a", "b")),
                "https://example.com/roles", "c", "roles", List.of("d", 1));
        assertEquals(java.util.Set.of("a", "b"), TokenCaller.roles(TokenCaller.claim(claims, "realm_access.roles")));
        assertEquals(java.util.Set.of("c"), TokenCaller.roles(TokenCaller.claim(claims, "https://example.com/roles")));
        assertEquals(java.util.Set.of("d"), TokenCaller.roles(TokenCaller.claim(claims, "roles")));
        assertEquals(java.util.Set.of(), TokenCaller.roles(TokenCaller.claim(claims, "realm_access.missing.x")));
    }
}
