package io.github.jungm.crema.internal.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetRetrievalException;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.ResourceRetriever;

import io.github.jungm.crema.internal.protocol.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

/**
 * The JWK set of an Authorization Server whose JWK set URL is read from its metadata: OpenID Connect Discovery
 * ({@code <issuer>/.well-known/openid-configuration}), falling back to RFC 8414
 * ({@code /.well-known/oauth-authorization-server} inserted before the issuer's path). The metadata's
 * {@code issuer} must equal the configured one. Once the JWK set URL is known, Nimbus'
 * {@link URLBasedJWKSetSource} retrieves the keys; Nimbus' caching, rate limiting, retrying and outage tolerance wrap
 * this source, so they apply to the metadata retrieval as well.
 */
final class IssuerJwkSetSource implements JWKSetSource<SecurityContext> {

    private final String issuer;
    private final ResourceRetriever retriever;
    private final Duration timeout;
    private final int sizeLimit;
    private volatile JWKSetSource<SecurityContext> delegate;

    /**
     * @param timeout the connect and read timeout of the metadata request
     * @param sizeLimit the maximum size of the metadata document, in bytes
     */
    IssuerJwkSetSource(String issuer, ResourceRetriever retriever, Duration timeout, int sizeLimit) {
        this.issuer = issuer;
        this.retriever = retriever;
        this.timeout = timeout;
        this.sizeLimit = sizeLimit;
    }

    @Override
    public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime,
            SecurityContext context) throws KeySourceException {
        JWKSetSource<SecurityContext> source = delegate;
        if (source == null) {
            synchronized (this) {
                source = delegate;
                if (source == null) {
                    URI jwksUri = discoverJwksUri();
                    try {
                        source = new URLBasedJWKSetSource<>(jwksUri.toURL(), retriever);
                    } catch (MalformedURLException | IllegalArgumentException e) {
                        throw new JWKSetRetrievalException("The jwks_uri " + jwksUri + " of issuer " + issuer
                                + " isn't a valid URL", e);
                    }
                    delegate = source;
                }
            }
        }
        return source.getJWKSet(refreshEvaluator, currentTime, context);
    }

    @Override
    public void close() throws IOException {
        JWKSetSource<SecurityContext> source = delegate;
        if (source != null) {
            source.close();
        }
    }

    /**
     * The metadata URLs to try, in order.
     */
    static List<URI> metadataUrls(String issuer) {
        URI uri = URI.create(issuer);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        return List.of(URI.create(origin + path + "/.well-known/openid-configuration"),
                URI.create(origin + "/.well-known/oauth-authorization-server" + path));
    }

    private URI discoverJwksUri() throws KeySourceException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        StringBuilder failures = new StringBuilder();
        for (URI url : metadataUrls(issuer)) {
            JsonObject metadata;
            try {
                metadata = fetch(http, url);
            } catch (IOException e) {
                failures.append("; ").append(url).append(": ").append(e.getMessage());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JWKSetRetrievalException("Interrupted while reading the metadata of issuer " + issuer, e);
            }
            if (!(metadata.get("issuer") instanceof JsonString metadataIssuer)
                    || !metadataIssuer.getString().equals(issuer)) {
                throw new JWKSetRetrievalException("The metadata at " + url + " names the issuer "
                        + metadata.get("issuer") + " instead of " + issuer, null);
            }
            if (!(metadata.get("jwks_uri") instanceof JsonString jwksUri)) {
                throw new JWKSetRetrievalException("The metadata at " + url + " has no jwks_uri", null);
            }
            try {
                URI uri = new URI(jwksUri.getString());
                if (!Protection.isFetchable(uri)) {
                    throw new JWKSetRetrievalException("The jwks_uri " + uri + " in the metadata at " + url
                            + " isn't an https URL", null);
                }
                return uri;
            } catch (URISyntaxException e) {
                throw new JWKSetRetrievalException("The jwks_uri in the metadata at " + url + " is invalid", e);
            }
        }
        throw new JWKSetRetrievalException("Couldn't read the metadata of issuer " + issuer + failures, null);
    }

    private JsonObject fetch(HttpClient http, URI url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(url).timeout(timeout)
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(sizeLimit + 1);
            if (bytes.length > sizeLimit) {
                throw new IOException("the document exceeds " + sizeLimit + " bytes");
            }
            try {
                if (Json.parse(bytes) instanceof JsonObject object) {
                    return object;
                }
            } catch (RuntimeException e) {
                throw new IOException("the document isn't JSON: " + e.getMessage());
            }
            throw new IOException("the document isn't a JSON object");
        }
    }
}
