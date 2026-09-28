package io.github.jungm.crema.it.security.app;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

/**
 * An unprotected resource of the application's REST API; reports the caller the Runtime sees.
 */
@Path("hello")
public class ApiResource {

    @Context
    SecurityContext securityContext;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "hello " + (securityContext.getUserPrincipal() == null ? "anonymous"
                : securityContext.getUserPrincipal().getName());
    }
}
