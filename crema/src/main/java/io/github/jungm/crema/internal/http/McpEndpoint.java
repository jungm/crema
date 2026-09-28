package io.github.jungm.crema.internal.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The resource class of an {@code McpApplication}. It exists only for the Runtimes: they deploy a JAX-RS
 * application only if it has a resource, and TomEE routes only the paths of its resources to it, so the resource
 * sits at the application path itself. {@link McpEndpointFilter} answers every request to an
 * {@code McpApplication} before resource matching, so this class is never matched there.
 * <p>
 * An application's own scanning {@code Application} may pick this class up as well. It has no resource method at
 * its own path, and its one sub-resource method has a path whose regular expression matches nothing, so it never
 * competes with the application's own root resource and never runs; if it did, it would answer {@code 404}.
 */
@Path("")
public class McpEndpoint {

    @GET
    @Path("{crema: (?!)}")
    public Response none() {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
