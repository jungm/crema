package io.github.jungm.crema.it.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal OpenID Connect provider in the test JVM, serving discovery and JWKS on a free loopback port.
 * The authorization and token endpoints exist only to be advertised; a Runtime is expected to redirect browsers
 * to the authorization endpoint, which the tests observe without following.
 */
public final class FakeOidcProvider implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private FakeOidcProvider(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakeOidcProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "fake-oidc-provider");
                thread.setDaemon(true);
                return thread;
            });
            FakeOidcProvider provider = new FakeOidcProvider(server, executor);
            server.createContext("/oidc", provider::handle);
            server.setExecutor(executor);
            server.start();
            return provider;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/oidc";
    }

    public String authorizationEndpoint() {
        return issuer() + "/authorize";
    }

    /** Request paths received so far, for diagnostics. */
    public List<String> requests() {
        return List.copyOf(requests);
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
            case "/oidc/.well-known/openid-configuration" -> respond(exchange, 200, "application/json", discovery());
            case "/oidc/jwks" -> respond(exchange, 200, "application/json", Jwts.jwks());
            case "/oidc/authorize" -> respond(exchange, 200, "text/plain", "fake login page");
            case "/oidc/token" -> respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            case "/oidc/userinfo" -> respond(exchange, 401, "application/json", "{\"error\":\"invalid_token\"}");
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

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
