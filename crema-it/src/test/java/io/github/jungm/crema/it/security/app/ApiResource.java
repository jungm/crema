package io.github.jungm.crema.it.security.app;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

@Path("hello")
@RolesAllowed("user")
public class ApiResource {

    @Context
    SecurityContext securityContext;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "hello " + securityContext.getUserPrincipal().getName();
    }
}
