package io.github.jungm.crema.internal.security;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An Authorization Server on a loopback port: OpenID Connect and RFC 8414 metadata, and a JWK set whose keys can be
 * rotated and whose endpoint can go down. Tokens are minted with Nimbus' signers.
 */
final class FakeAuthorizationServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private volatile List<JWK> published;
    private volatile boolean jwksDown;
    private volatile boolean oidcDiscovery = true;
    private volatile String advertisedIssuer;
    final RSAKey rsa = rsa("rsa-1");
    final ECKey ec = ec("ec-1");

    FakeAuthorizationServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        published = List.of(rsa.toPublicJWK(), ec.toPublicJWK());
        server.createContext("/", this::handle);
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realm";
    }

    String jwksUri() {
        return issuer() + "/jwks";
    }

    int jwksRequests() {
        return jwksRequests.get();
    }

    /**
     * Publishes these keys (their public parts) from now on.
     */
    void publish(JWK... keys) {
        published = List.of(keys).stream().map(JWK::toPublicJWK).toList();
    }

    void jwksDown(boolean down) {
        jwksDown = down;
    }

    /**
     * Whether {@code /.well-known/openid-configuration} exists; RFC 8414 metadata always does.
     */
    void oidcDiscovery(boolean enabled) {
        oidcDiscovery = enabled;
    }

    /**
     * Makes the metadata name another issuer.
     */
    void advertiseIssuer(String issuer) {
        advertisedIssuer = issuer;
    }

    static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static ECKey ec(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A valid RS256 access token for an audience, signed with {@link #rsa}: subject {@code alice}, group
     * {@code user}, expiring in five minutes.
     */
    String token(String audience) {
        return token(audience, claims -> {
        });
    }

    String token(String audience, Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(), rsaSigner(rsa), claims(audience, customizer));
    }

    JWTClaimsSet claims(String audience, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("alice")
                .audience(audience)
                .claim("groups", List.of("user"))
                .claim("email", "alice@example.com")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        return claims.build();
    }

    static String sign(JWSHeader header, JWSSigner signer, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    static JWSSigner rsaSigner(RSAKey key) {
        try {
            return new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static JWSSigner ecSigner(ECKey key) {
        try {
            return new ECDSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String issuer = issuer();
        String metadata = "{\"issuer\":\"" + (advertisedIssuer != null ? advertisedIssuer : issuer) + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + jwksUri() + "\"}";
        if (path.equals("/realm/.well-known/openid-configuration") && oidcDiscovery) {
            respond(exchange, 200, metadata);
        } else if (path.equals("/.well-known/oauth-authorization-server/realm")) {
            respond(exchange, 200, metadata);
        } else if (path.equals("/realm/jwks")) {
            jwksRequests.incrementAndGet();
            if (jwksDown) {
                respond(exchange, 503, "{}");
            } else {
                respond(exchange, 200, new JWKSet(published).toString());
            }
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
