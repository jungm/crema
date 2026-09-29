package io.github.jungm.crema.internal.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import io.github.jungm.crema.internal.protocol.Rejection;

/**
 * What the mechanisms share: {@code 401} challenges, and logging why credentials were rejected. All mechanisms log
 * under the name of {@link CremaAccessPolicy}, so one logger covers every rejection.
 */
final class Challenges {

    static final Logger LOG = Logger.getLogger(CremaAccessPolicy.class.getName());
    static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private Challenges() {
    }

    /**
     * A {@code 401} with a {@code Bearer} challenge; without {@code scope} and {@code resource_metadata} if there is
     * no OAuth protection.
     *
     * @param error the {@code error} parameter, or {@code null}
     */
    static Rejection bearer(Protection protection, String error) {
        List<String> parameters = new ArrayList<>();
        if (error != null) {
            parameters.add("error=\"" + error + "\"");
        }
        if (protection != null) {
            if (!protection.scopes().isEmpty()) {
                parameters.add("scope=\"" + String.join(" ", protection.scopes()) + "\"");
            }
            parameters.add("resource_metadata=\"" + protection.resourceMetadataUrl() + "\"");
        }
        return new Rejection(401, Map.of(WWW_AUTHENTICATE,
                parameters.isEmpty() ? "Bearer" : "Bearer " + String.join(", ", parameters)), null);
    }

    /**
     * Logs a failure to authenticate that isn't the credentials' fault and that any client can trigger: as a
     * warning without stack trace at most once per minute and MCP Server, and else at {@code FINE}.
     */
    static void failure(LogText.Throttle failures, String message) {
        if (failures.permit()) {
            LOG.warning(message + " (further such warnings are suppressed for a minute)");
        } else {
            LOG.fine(message);
        }
    }
}
