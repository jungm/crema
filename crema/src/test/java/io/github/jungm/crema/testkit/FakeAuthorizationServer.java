package io.github.jungm.crema.testkit;

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
import java.util.concurrent.TimeUnit;
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
 * An Authorization Server in the test JVM, on a loopback port, for the unit tests of {@code crema} and the
 * integration tests of {@code crema-it}:
 * <ul>
 * <li>OpenID Connect discovery ({@code <issuer>/.well-known/openid-configuration}) and RFC 8414 metadata
 * ({@code /.well-known/oauth-authorization-server/realm});</li>
 * <li>a JWK set ({@link #jwksUri()}) whose keys can be rotated and whose endpoint can go down;</li>
 * <li>authorization, token and userinfo endpoints that exist only to be advertised to an OpenID Connect client (the
 * tests observe the redirect to the authorization endpoint without following it);</li>
 * <li>faults: stalled metadata, redirects to {@code /moved/...}, metadata that names another issuer.</li>
 * </ul>
 * Tokens are minted with Nimbus' signers.
 */
public final class FakeAuthorizationServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger metadataRequests = new AtomicInteger();
    private final AtomicInteger movedRequests = new AtomicInteger();
    private final RSAKey rsa = rsa("rsa-1");
    private final ECKey ec = ec("ec-1");
    private volatile List<JWK> published = List.of(rsa.toPublicJWK(), ec.toPublicJWK());
    private volatile boolean jwksDown;
    private volatile boolean oidcDiscovery = true;
    private volatile String advertisedIssuer;
    private volatile boolean metadataStalled;
    private volatile boolean redirecting;

    private FakeAuthorizationServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakeAuthorizationServer start() {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-authorization-server");
            thread.setDaemon(true);
            return thread;
        });
        FakeAuthorizationServer as = new FakeAuthorizationServer(server, executor);
        server.createContext("/", as::handle);
        server.setExecutor(executor);
        server.start();
        return as;
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realm";
    }

    public String jwksUri() {
        return issuer() + "/jwks";
    }

    public String authorizationEndpoint() {
        return issuer() + "/authorize";
    }

    /**
     * The RSA key that signs {@link #token(String)}; published from the start.
     */
    public RSAKey rsaKey() {
        return rsa;
    }

    /**
     * An EC P-256 key, published from the start.
     */
    public ECKey ecKey() {
        return ec;
    }

    /**
     * Request lines received so far, for diagnostics.
     */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public int jwksRequests() {
        return jwksRequests.get();
    }

    public int metadataRequests() {
        return metadataRequests.get();
    }

    /**
     * How many requests reached {@code /moved/...}.
     */
    public int movedRequests() {
        return movedRequests.get();
    }

    /**
     * Publishes these keys (their public parts) from now on.
     */
    public void publish(JWK... keys) {
        published = List.of(keys).stream().map(JWK::toPublicJWK).toList();
    }

    /**
     * Whether the JWK set endpoint answers {@code 503}.
     */
    public void jwksDown(boolean down) {
        jwksDown = down;
    }

    /**
     * Whether {@code /.well-known/openid-configuration} exists; RFC 8414 metadata always does.
     */
    public void oidcDiscovery(boolean enabled) {
        oidcDiscovery = enabled;
    }

    /**
     * Whether metadata responses stall after their headers and the first bytes of the body, until this is
     * switched off again.
     */
    public void stallMetadata(boolean stall) {
        metadataStalled = stall;
    }

    /**
     * Whether the metadata and JWK set endpoints answer with a redirect to {@code /moved/...}, where the same
     * documents are served.
     */
    public void redirect(boolean enabled) {
        redirecting = enabled;
    }

    /**
     * Makes the metadata name another issuer.
     */
    public void advertiseIssuer(String issuer) {
        advertisedIssuer = issuer;
    }

    public static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ECKey ec(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A valid RS256 access token for an audience, signed with {@link #rsaKey()}: subject {@code alice}, group
     * {@code user}, expiring in five minutes.
     */
    public String token(String audience) {
        return token(audience, claims -> {
        });
    }

    public String token(String audience, Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(rsa, JWSAlgorithm.RS256, claims(audience, customizer));
    }

    public JWTClaimsSet claims(String audience, Consumer<JWTClaimsSet.Builder> customizer) {
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

    /**
     * A token signed with an RSA key, with {@code typ} {@code at+jwt} and the key's {@code kid}.
     */
    public static String sign(RSAKey key, JWSAlgorithm algorithm, JWTClaimsSet claims) {
        return sign(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(new JOSEObjectType("at+jwt"))
                .build(), rsaSigner(key), claims);
    }

    public static String sign(JWSHeader header, JWSSigner signer, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static JWSSigner rsaSigner(RSAKey key) {
        try {
            return new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JWSSigner ecSigner(ECKey key) {
        try {
            return new ECDSASigner(key);
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
        if (path.startsWith("/moved/")) {
            movedRequests.incrementAndGet();
            path = path.substring("/moved".length());
        } else if (redirecting) {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/moved" + path);
            respond(exchange, 302, "application/json", "{}");
            return;
        }
        if (path.equals("/realm/.well-known/openid-configuration") && oidcDiscovery
                || path.equals("/.well-known/oauth-authorization-server/realm")) {
            metadataRequests.incrementAndGet();
            if (metadataStalled) {
                stall(exchange, metadata());
            } else {
                respond(exchange, 200, "application/json", metadata());
            }
            return;
        }
        switch (path) {
            case "/realm/jwks" -> {
                jwksRequests.incrementAndGet();
                if (jwksDown) {
                    respond(exchange, 503, "application/json", "{}");
                } else {
                    respond(exchange, 200, "application/json", new JWKSet(published).toString());
                }
            }
            case "/realm/authorize" -> respond(exchange, 200, "text/plain", "fake login page");
            case "/realm/token" -> respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            case "/realm/userinfo" -> respond(exchange, 401, "application/json", "{\"error\":\"invalid_token\"}");
            default -> respond(exchange, 404, "application/json", "{}");
        }
    }

    private String metadata() {
        String issuer = issuer();
        return "{"
                + "\"issuer\":\"" + (advertisedIssuer != null ? advertisedIssuer : issuer) + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"userinfo_endpoint\":\"" + issuer + "/userinfo\","
                + "\"jwks_uri\":\"" + jwksUri() + "\","
                + "\"end_session_endpoint\":\"" + issuer + "/logout\","
                + "\"response_types_supported\":[\"code\",\"id_token\",\"token id_token\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                + "\"scopes_supported\":[\"openid\",\"email\",\"profile\"],"
                + "\"token_endpoint_auth_methods_supported\":[\"client_secret_basic\",\"client_secret_post\"],"
                + "\"claims_supported\":[\"sub\",\"iss\",\"aud\",\"exp\",\"iat\",\"name\",\"email\",\"groups\"]"
                + "}";
    }

    private void stall(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.substring(0, 10).getBytes(StandardCharsets.UTF_8));
            out.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (metadataStalled && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
