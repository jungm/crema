package io.github.jungm.crema.it.support;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mints RS256 JWTs with JDK crypto only. {@link #TRUSTED} is the key pair whose public key the test WAR
 * configures in {@code mp.jwt.verify.publickey}; {@link #UNTRUSTED} is unknown to the Runtime.
 */
public final class Jwts {

    public static final String ISSUER = "https://issuer.crema.test";
    public static final String MCP_AUDIENCE = "https://mcp.crema.test";
    public static final String API_AUDIENCE = "https://api.crema.test";
    public static final String KEY_ID = "crema-it";

    public static final KeyPair TRUSTED = generate();
    public static final KeyPair UNTRUSTED = generate();

    private Jwts() {
    }

    /** The trusted public key in PEM format (X.509 SubjectPublicKeyInfo). */
    public static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(TRUSTED.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** The trusted public key as a JWKS document. */
    public static String jwks() {
        RSAPublicKey key = (RSAPublicKey) TRUSTED.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + KEY_ID + "\","
                + "\"n\":\"" + b64url(unsigned(key.getModulus())) + "\","
                + "\"e\":\"" + b64url(unsigned(key.getPublicExponent())) + "\"}]}";
    }

    public static Builder token() {
        return new Builder();
    }

    public static final class Builder {
        private String issuer = ISSUER;
        private List<String> audience = List.of(MCP_AUDIENCE);
        private String upn = "alice";
        private List<String> groups = List.of("user");
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private PrivateKey key = TRUSTED.getPrivate();

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String... audience) {
            this.audience = List.of(audience);
            return this;
        }

        public Builder upn(String upn) {
            this.upn = upn;
            return this;
        }

        public Builder groups(String... groups) {
            this.groups = List.of(groups);
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder signedWith(PrivateKey key) {
            this.key = key;
            return this;
        }

        public String build() {
            Instant issuedAt = expiresAt.minusSeconds(600);
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
            String payload = "{"
                    + "\"iss\":\"" + issuer + "\","
                    + "\"sub\":\"" + upn + "\","
                    + "\"upn\":\"" + upn + "\","
                    + "\"preferred_username\":\"" + upn + "\","
                    + "\"aud\":" + jsonArray(audience) + ","
                    + "\"groups\":" + jsonArray(groups) + ","
                    + "\"jti\":\"" + UUID.randomUUID() + "\","
                    + "\"iat\":" + issuedAt.getEpochSecond() + ","
                    + "\"auth_time\":" + issuedAt.getEpochSecond() + ","
                    + "\"exp\":" + expiresAt.getEpochSecond()
                    + "}";
            String signingInput = b64url(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + b64url(payload.getBytes(StandardCharsets.UTF_8));
            try {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(key);
                signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return signingInput + "." + b64url(signature.sign());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
