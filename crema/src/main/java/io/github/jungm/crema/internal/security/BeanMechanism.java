package io.github.jungm.crema.internal.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.github.jungm.crema.McpAuthentication;
import io.github.jungm.crema.McpAuthenticator;
import io.github.jungm.crema.internal.model.InstanceSource;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Rejection;

/**
 * Authentication by the application's {@link McpAuthenticator} bean. Rejected credentials, a failing authenticator
 * and a header field it reads that has no single value are answered with an {@code invalid_token} challenge;
 * challenges don't point to Protected Resource Metadata, since there is none.
 */
public final class BeanMechanism implements Mechanism {

    private static final Rejection UNAUTHENTICATED = Challenges.bearer(null, null);
    private static final Rejection INVALID = Challenges.bearer(null, "invalid_token");

    private final Authenticator authenticator;
    private final LogText.Throttle failures = new LogText.Throttle(1, TimeUnit.MINUTES);

    public BeanMechanism(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Optional<Caller> authenticate(McpServerModel server, Function<String, List<String>> headers) {
        Credentials credentials = new Credentials(server.wireName(), headers);
        McpAuthentication result;
        try (InstanceSource.Handle handle = authenticator.instances().acquire()) {
            result = ((McpAuthenticator) handle.get()).authenticate(credentials);
        } catch (RuntimeException e) {
            Challenges.failure(failures, "Rejected a request to MCP Server '" + server.wireName() + "' because "
                    + authenticator.describe() + " failed: " + e.getClass().getName() + ": "
                    + LogText.clean(e.getMessage()));
            throw INVALID;
        }
        if (result == null) {
            Challenges.failure(failures, "Rejected a request to MCP Server '" + server.wireName() + "' because "
                    + authenticator.describe() + " returned null");
            throw INVALID;
        }
        if (credentials.ambiguity() != null) {
            Challenges.LOG.fine(() -> "Rejected a request to MCP Server '" + server.wireName() + "': "
                    + credentials.ambiguity());
            throw INVALID;
        }
        switch (result.outcome()) {
        case AUTHENTICATED:
            return Optional.of(new CremaCaller(server.application(),
                    new CallerPrincipal(result.name(), result.claims()), result.roles()));
        case REJECTED:
            Challenges.LOG.fine(() -> "Rejected a request to MCP Server '" + server.wireName() + "': "
                    + authenticator.describe() + " rejected its credentials");
            throw INVALID;
        default:
            return Optional.empty();
        }
    }

    @Override
    public Rejection unauthenticated() {
        return UNAUTHENTICATED;
    }
}
