package io.github.jungm.crema.internal.security;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.jungm.crema.McpCredentials;

/**
 * The header fields of one request, as an {@code McpAuthenticator} reads them. It remembers whether the
 * authenticator read a header field that has no single value, which makes the request rejected.
 */
final class Credentials implements McpCredentials {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer";

    /**
     * The syntax of RFC 6750 §2.1 {@code b64token}: the only characters a bearer token may have. A value with
     * whitespace or anything else, such as two tokens or two joined {@code Authorization} headers, is invalid.
     */
    static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

    private final String server;
    private final Function<String, List<String>> headers;
    private String ambiguity;

    /**
     * @param headers the values of a header field by case-insensitive name; empty when it is absent
     */
    Credentials(String server, Function<String, List<String>> headers) {
        this.server = server;
        this.headers = headers;
    }

    @Override
    public String server() {
        return server;
    }

    @Override
    public Optional<String> header(String name) {
        List<String> values = headers.apply(name);
        if (values.size() > 1) {
            ambiguity = "the request repeats the header field " + name;
            return Optional.empty();
        }
        return values.stream().findFirst();
    }

    @Override
    public Optional<String> bearerToken() {
        Optional<String> header = header(AUTHORIZATION);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        String value = header.get().strip();
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase(BEARER)) {
            return Optional.empty();
        }
        String token = space < 0 ? "" : value.substring(space + 1).stripLeading();
        if (!B64TOKEN.matcher(token).matches()) {
            ambiguity = "the bearer token is empty or has characters outside the RFC 6750 b64token syntax";
            return Optional.empty();
        }
        return Optional.of(token);
    }

    /**
     * Why the request is rejected whatever the authenticator decided, or {@code null}.
     */
    String ambiguity() {
        return ambiguity;
    }
}
