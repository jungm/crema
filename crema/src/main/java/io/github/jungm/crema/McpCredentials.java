package io.github.jungm.crema;

import java.util.Optional;

/**
 * What an {@link McpAuthenticator} may read of a request to an MCP Endpoint: its header fields. The request body is
 * read only after authentication.
 * <p>
 * A header field that a request repeats has no single value. Reading it gives an empty result and makes Crema
 * reject the request, whatever the authenticator returns, so that two credentials can't be played off against each
 * other. The same applies to an {@code Authorization: Bearer} header whose token isn't in the syntax of RFC 6750.
 */
public interface McpCredentials {

    /**
     * The name of the MCP Server the request is for, as MCP Clients see it: {@code default} for the default MCP
     * Server.
     */
    String server();

    /**
     * The value of a header field, by case-insensitive name; empty if the request doesn't have it or repeats it.
     */
    Optional<String> header(String name);

    /**
     * The token of the request's {@code Authorization: Bearer} header, as in RFC 6750; empty if the request has no
     * {@code Authorization} header, one with another scheme, or one that is repeated or malformed.
     */
    Optional<String> bearerToken();
}
