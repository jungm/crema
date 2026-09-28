package io.github.jungm.crema.internal.security;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetRetrievalException;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.ResourceRetriever;

import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

/**
 * The JWK set of an Authorization Server whose JWK set URL is read from its metadata: OpenID Connect Discovery
 * ({@code <issuer>/.well-known/openid-configuration}), falling back to RFC 8414
 * ({@code /.well-known/oauth-authorization-server} inserted before the issuer's path). The metadata's
 * {@code issuer} must equal the configured one. The metadata and the keys are retrieved with the same Nimbus
 * {@link ResourceRetriever}, so the same timeouts and size limit apply to both; once the JWK set URL is known,
 * Nimbus' {@link URLBasedJWKSetSource} retrieves the keys. Nimbus' caching, rate limiting, retrying and outage
 * tolerance wrap this source, so they apply to the metadata retrieval as well.
 * <p>
 * No lock is held while the metadata is retrieved: concurrent first retrievals may each read the metadata, and
 * the first JWK set URL found is kept.
 */
final class IssuerJwkSetSource implements JWKSetSource<SecurityContext> {

    private final String issuer;
    private final ResourceRetriever retriever;
    private final AtomicReference<JWKSetSource<SecurityContext>> delegate = new AtomicReference<>();

    IssuerJwkSetSource(String issuer, ResourceRetriever retriever) {
        this.issuer = issuer;
        this.retriever = retriever;
    }

    @Override
    public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime,
            SecurityContext context) throws KeySourceException {
        JWKSetSource<SecurityContext> source = delegate.get();
        if (source == null) {
            URI jwksUri = discoverJwksUri();
            try {
                delegate.compareAndSet(null, new URLBasedJWKSetSource<>(jwksUri.toURL(), retriever));
            } catch (MalformedURLException | IllegalArgumentException e) {
                throw new JWKSetRetrievalException("The jwks_uri " + jwksUri + " of issuer " + issuer
                        + " isn't a valid URL", e);
            }
            source = delegate.get();
        }
        return source.getJWKSet(refreshEvaluator, currentTime, context);
    }

    @Override
    public void close() throws IOException {
        JWKSetSource<SecurityContext> source = delegate.get();
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

    /**
     * Whether a {@code jwks_uri} from the metadata may be used: {@code https}, or {@code http} to a loopback host
     * only if the issuer is on a loopback host too, so that the metadata of a remote Authorization Server can't
     * point Crema at local plain-text services.
     */
    static boolean isAcceptableJwksUri(URI jwksUri, String issuer) {
        if (!Protection.isFetchable(jwksUri)) {
            return false;
        }
        if (jwksUri.getScheme().equalsIgnoreCase("https")) {
            return true;
        }
        String issuerHost = URI.create(issuer).getHost();
        return Loopback.isHost(issuerHost);
    }

    private URI discoverJwksUri() throws KeySourceException {
        StringBuilder failures = new StringBuilder();
        for (URI url : metadataUrls(issuer)) {
            JsonObject metadata;
            try {
                metadata = fetch(url);
            } catch (IOException e) {
                failures.append("; ").append(url).append(": ").append(e.getMessage());
                continue;
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
                if (!isAcceptableJwksUri(uri, issuer)) {
                    throw new JWKSetRetrievalException("The jwks_uri " + uri + " in the metadata at " + url
                            + " isn't an https URL (http only for localhost, if the issuer is too)", null);
                }
                return uri;
            } catch (URISyntaxException e) {
                throw new JWKSetRetrievalException("The jwks_uri in the metadata at " + url + " is invalid", e);
            }
        }
        throw new JWKSetRetrievalException("Couldn't read the metadata of issuer " + issuer + failures, null);
    }

    private JsonObject fetch(URI url) throws IOException {
        String content = retriever.retrieveResource(url.toURL()).getContent();
        try {
            if (Json.parse(content) instanceof JsonObject object) {
                return object;
            }
        } catch (RuntimeException e) {
            throw new IOException("the document isn't JSON: " + e.getMessage());
        }
        throw new IOException("the document isn't a JSON object");
    }
}
