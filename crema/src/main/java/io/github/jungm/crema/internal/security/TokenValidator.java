package io.github.jungm.crema.internal.security;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Validates the bearer tokens of one protected MCP Server. All JOSE work is Nimbus JOSE+JWT's:
 * <ul>
 * <li>keys come from a {@link JWKSourceBuilder} source with caching, refresh-ahead, rate limiting, one retry and
 * outage tolerance; a token with an unknown {@code kid} makes it refresh the JWK set (subject to rate limiting),
 * which picks up rotated keys;</li>
 * <li>a {@link DefaultJWTProcessor} accepts only JWS tokens signed with {@link #ALGORITHMS} (no {@code none}, no
 * HMAC, no JWE), with a {@code typ} of {@code at+jwt}, {@code JWT} or none;</li>
 * <li>a {@link DefaultJWTClaimsVerifier} requires {@code iss} to equal the issuer, {@code aud} to contain the
 * Resource Identifier, and {@code exp} to be present, and checks {@code exp} and {@code nbf} with the configured
 * clock skew.</li>
 * </ul>
 */
final class TokenValidator implements Closeable {

    /**
     * The accepted signature algorithms: asymmetric only.
     */
    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384,
            JWSAlgorithm.ES512, JWSAlgorithm.EdDSA);

    private static final JOSEObjectTypeVerifier<SecurityContext> TYPES = new DefaultJOSEObjectTypeVerifier<>(
            new JOSEObjectType("at+jwt"), JOSEObjectType.JWT, null);

    /**
     * How keys are retrieved and cached.
     *
     * @param cacheTtl how long a retrieved JWK set is used before it is retrieved again
     * @param refreshTimeout how long a request waits for a JWK set retrieval in progress
     * @param refreshAhead how long before expiry a request triggers a background retrieval
     * @param rateLimit the minimum interval between two retrievals beyond the first two
     * @param httpTimeout the connect and read timeout of each HTTP request
     * @param sizeLimit the maximum size of a retrieved document, in bytes
     */
    record Tuning(Duration cacheTtl, Duration refreshTimeout, Duration refreshAhead, Duration rateLimit,
            Duration httpTimeout, int sizeLimit) {

        static final Tuning DEFAULT = new Tuning(Duration.ofMillis(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE),
                Duration.ofMillis(JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT),
                Duration.ofMillis(JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME),
                Duration.ofMillis(JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL), Duration.ofSeconds(5),
                JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT);
    }

    private final Protection protection;
    private final JWKSource<SecurityContext> keys;
    private final JWSKeySelector<SecurityContext> keySelector;

    TokenValidator(Protection protection, Tuning tuning) {
        this.protection = protection;
        int timeout = (int) tuning.httpTimeout().toMillis();
        ResourceRetriever retriever = new DefaultResourceRetriever(timeout, timeout, tuning.sizeLimit());
        JWKSetSource<SecurityContext> source;
        if (protection.jwksUri() != null) {
            try {
                source = new URLBasedJWKSetSource<>(protection.jwksUri().toURL(), retriever);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        } else {
            source = new IssuerJwkSetSource(protection.issuer(), retriever, tuning.httpTimeout(),
                    tuning.sizeLimit());
        }
        ExecutorService refresher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crema-jwks-refresh-" + protection.server());
            thread.setDaemon(true);
            return thread;
        });
        this.keys = JWKSourceBuilder.create(source)
                .cache(tuning.cacheTtl().toMillis(), tuning.refreshTimeout().toMillis())
                .refreshAheadCache(tuning.refreshAhead().toMillis(), null, refresher, true, null, false)
                .rateLimited(tuning.rateLimit().toMillis())
                .retrying(true)
                .outageTolerant(true)
                .build();
        this.keySelector = new JWSVerificationKeySelector<>(ALGORITHMS, keys);
    }

    /**
     * Validates a token for a Resource Identifier.
     *
     * @return the token's claims
     * @throws ParseException if the token isn't a JWT
     * @throws BadJOSEException if the token is rejected
     * @throws JOSEException if the token can't be verified, for example because the keys can't be retrieved
     */
    JWTClaimsSet validate(String token, String resource) throws ParseException, BadJOSEException, JOSEException {
        Set<String> required = new HashSet<>(Set.of(JWTClaimNames.EXPIRATION_TIME));
        if (!protection.principalClaim().contains(".")) {
            required.add(protection.principalClaim());
        }
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(Set.of(resource),
                new JWTClaimsSet.Builder().issuer(protection.issuer()).build(), required, null);
        claims.setMaxClockSkew(protection.clockSkewSeconds());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(TYPES);
        processor.setJWSKeySelector(keySelector);
        processor.setJWTClaimsSetVerifier(claims);
        return processor.process(token, null);
    }

    @Override
    public void close() throws IOException {
        if (keys instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
