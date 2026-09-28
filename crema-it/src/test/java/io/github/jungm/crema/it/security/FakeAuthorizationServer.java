package io.github.jungm.crema.it.security;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An Authorization Server in the test JVM, on a loopback port: OpenID Connect discovery, a JWK set whose keys can
 * be rotated, and authorization and token endpoints that exist only to be advertised (the tests observe the
 * redirect to the authorization endpoint without following it). Tokens are minted with Nimbus' signers.
 */
final class FakeAuthorizationServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile List<JWK> published;
    final RSAKey key = rsa("it-1");

    private FakeAuthorizationServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.published = List.of(key.toPublicJWK());
    }

    static FakeAuthorizationServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "fake-authorization-server");
                thread.setDaemon(true);
                return thread;
            });
            FakeAuthorizationServer as = new FakeAuthorizationServer(server, executor);
            server.createContext("/", as::handle);
            server.setExecutor(executor);
            server.start();
            return as;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realm";
    }

    String authorizationEndpoint() {
        return issuer() + "/authorize";
    }

    /**
     * Request lines received so far, for diagnostics.
     */
    List<String> requests() {
        return List.copyOf(requests);
    }

    /**
     * Publishes these keys (their public parts) from now on.
     */
    void publish(JWK... keys) {
        published = List.of(keys).stream().map(JWK::toPublicJWK).toList();
    }

    static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A valid access token for an audience, signed with {@link #key}: subject {@code alice}, group {@code user},
     * expiring in five minutes.
     */
    String token(String audience) {
        return token(audience, claims -> {
        });
    }

    String token(String audience, Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(key, JWSAlgorithm.RS256, claims(audience, customizer));
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

    static String sign(RSAKey key, JWSAlgorithm algorithm, JWTClaimsSet claims) {
        return sign(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(new JOSEObjectType("at+jwt"))
                .build(), rsaSigner(key), claims);
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

    private static JWSSigner rsaSigner(RSAKey key) {
        try {
            return new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        switch (path) {
            case "/realm/.well-known/openid-configuration" -> respond(exchange, 200, "application/json",
                    discovery());
            case "/realm/jwks" -> respond(exchange, 200, "application/json", new JWKSet(published).toString());
            case "/realm/authorize" -> respond(exchange, 200, "text/plain", "fake login page");
            case "/realm/token" -> respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            case "/realm/userinfo" -> respond(exchange, 401, "application/json", "{\"error\":\"invalid_token\"}");
            default -> respond(exchange, 404, "text/plain", "not found");
        }
    }

    private String discovery() {
        String issuer = issuer();
        return "{"
                + "\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"userinfo_endpoint\":\"" + issuer + "/userinfo\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"end_session_endpoint\":\"" + issuer + "/logout\","
                + "\"response_types_supported\":[\"code\",\"id_token\",\"token id_token\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                + "\"scopes_supported\":[\"openid\",\"email\",\"profile\"],"
                + "\"token_endpoint_auth_methods_supported\":[\"client_secret_basic\",\"client_secret_post\"],"
                + "\"claims_supported\":[\"sub\",\"iss\",\"aud\",\"exp\",\"iat\",\"name\",\"email\",\"groups\"]"
                + "}";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
