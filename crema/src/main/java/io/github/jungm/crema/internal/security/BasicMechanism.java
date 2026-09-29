package io.github.jungm.crema.internal.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;

/**
 * HTTP Basic authentication (RFC 7617) against users configured with plaintext passwords. A request without
 * {@code Basic} credentials has none; malformed credentials, a repeated {@code Authorization} header, an unknown user
 * and a wrong password are answered with the same {@code 401} challenge as a request without credentials, so the
 * response doesn't tell whether a user exists. Passwords are compared by their SHA-256 digests with
 * {@link MessageDigest#isEqual}, which takes the same time whatever the password is.
 */
public final class BasicMechanism implements Mechanism {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BASIC = "Basic";

    /**
     * A user.
     *
     * @param password the plaintext password
     */
    public record User(String password, Set<String> roles) {

        public User {
            roles = Set.copyOf(roles);
        }

        @Override
        public String toString() {
            return "User[roles=" + roles + "]";
        }
    }

    private record Digested(byte[] password, Set<String> roles) {
    }

    /**
     * Compared against when the user is unknown, so that an unknown user takes as long as a wrong password.
     */
    private static final byte[] UNKNOWN = digest("");

    private final Map<String, Digested> users;
    private final Rejection challenge;

    /**
     * @param realm the realm of the challenge: the MCP Server's name
     */
    public BasicMechanism(String realm, Map<String, User> users) {
        this.users = users.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> new Digested(digest(entry.getValue().password()), entry.getValue().roles())));
        this.challenge = new Rejection(401, Map.of(Challenges.WWW_AUTHENTICATE, "Basic realm=\"" + quote(realm)
                + "\", charset=\"UTF-8\""), null);
    }

    @Override
    public Optional<Caller> authenticate(McpServerModel server, Function<String, List<String>> headers) {
        List<String> authorization = headers.apply(AUTHORIZATION);
        if (authorization.isEmpty()) {
            return Optional.empty();
        }
        if (authorization.size() > 1) {
            throw reject(server, "it has more than one Authorization header");
        }
        String value = authorization.get(0).strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase(BASIC)) {
            return Optional.empty();
        }
        String credentials = decode(space < 0 ? "" : value.substring(space + 1).strip());
        if (credentials == null) {
            throw reject(server, "its Basic credentials aren't Base64-encoded UTF-8");
        }
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            throw reject(server, "its Basic credentials have no ':'");
        }
        String name = credentials.substring(0, colon);
        Digested user = users.get(name);
        boolean matches = MessageDigest.isEqual(user == null ? UNKNOWN : user.password(),
                digest(credentials.substring(colon + 1)));
        if (user == null || !matches) {
            throw reject(server, user == null ? "the user is unknown" : "the password is wrong");
        }
        return Optional.of(new CremaCaller(server.application(), new CallerPrincipal(name, Map.of()),
                user.roles()));
    }

    @Override
    public Rejection unauthenticated() {
        return challenge;
    }

    /**
     * Logs why credentials were rejected, and returns the challenge to throw.
     */
    private Rejection reject(McpServerModel server, String reason) {
        Challenges.LOG.fine(() -> "Rejected Basic credentials for MCP Server '" + server.wireName() + "': " + reason);
        return challenge;
    }

    /**
     * The Base64-decoded UTF-8 text of Basic credentials, or {@code null} if they aren't.
     */
    private static String decode(String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            CharBuffer text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return text.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] digest(String password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform supports SHA-256", e);
        }
    }

    private static String quote(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
