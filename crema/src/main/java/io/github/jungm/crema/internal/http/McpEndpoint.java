package io.github.jungm.crema.internal.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The resource class of an {@code McpApplication}, which exists only because the Runtimes deploy a JAX-RS
 * application only if it has a resource. {@link McpEndpointFilter} answers every request to an
 * {@code McpApplication} before resource matching, so this class is never matched there.
 * <p>
 * An application's own scanning {@code Application} may pick this class up as well. Its path is a regular
 * expression that matches no path at all, so it never competes with the application's resources, and it never
 * runs; if it did, it would answer {@code 404}.
 */
@Path("{crema: (?!)}")
public class McpEndpoint {

    @GET
    public Response none() {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
