package io.github.jungm.crema.it.coexistence.app;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The application's root resource, at the same {@code @Path("")} as Crema's MCP Endpoint. */
@Path("")
public class ApiRoot {

    public record Status(String serviceName, int openOrders) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Status status() {
        return new Status("shop", 3);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Status echo(Status status) {
        return new Status(status.serviceName() + "-echo", status.openOrders() + 1);
    }

    @GET
    @Path("boom")
    @Produces(MediaType.TEXT_PLAIN)
    public String boom() {
        throw new IllegalStateException("boom");
    }
}
