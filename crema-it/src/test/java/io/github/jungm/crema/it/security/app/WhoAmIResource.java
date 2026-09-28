package io.github.jungm.crema.it.security.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports the caller as {@code key=value} lines without enforcing anything. Exceptions thrown by the Runtime while
 * resolving the caller are caught and reported, the way Crema has to handle them to produce its own 401.
 */
@Path("whoami")
public class WhoAmIResource {

    @Context
    SecurityContext securityContext;

    @Context
    HttpServletRequest request;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String whoAmI() {
        StringBuilder out = new StringBuilder();
        line(out, "reached", "true");
        Principal principal = null;
        try {
            principal = securityContext.getUserPrincipal();
        } catch (RuntimeException e) {
            line(out, "principalError", e.getClass().getName() + ": " + e.getMessage());
        }
        line(out, "name", principal == null ? "anonymous" : principal.getName());
        line(out, "principalClass", principal == null ? "" : principal.getClass().getName());
        line(out, "jwt", String.valueOf(principal instanceof JsonWebToken));
        line(out, "aud", principal instanceof JsonWebToken jwt ? join(jwt.getAudience()) : "");
        line(out, "groups", principal instanceof JsonWebToken jwt ? join(jwt.getGroups()) : "");
        if (principal != null) {
            line(out, "inRoleUser", String.valueOf(securityContext.isUserInRole("user")));
            line(out, "authScheme", String.valueOf(securityContext.getAuthenticationScheme()));
            Principal servletPrincipal = request.getUserPrincipal();
            line(out, "servletName", servletPrincipal == null ? "anonymous" : servletPrincipal.getName());
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value.replace('\n', ' ')).append('\n');
    }

    private static String join(Set<String> values) {
        return values == null ? "" : String.join(",", new TreeSet<>(values));
    }
}
