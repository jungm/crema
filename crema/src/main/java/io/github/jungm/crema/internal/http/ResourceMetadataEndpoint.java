package io.github.jungm.crema.internal.http;

import java.util.Optional;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

/**
 * Serves the Protected Resource Metadata (RFC 9728) of a protected MCP Server at
 * {@code <MCP Endpoint>/.well-known/oauth-protected-resource}, unauthenticated and without the {@code Origin} check.
 * Other MCP Servers, and applications that aren't an {@code McpApplication}, answer {@code 404}.
 */
@Path(".well-known/oauth-protected-resource")
public class ResourceMetadataEndpoint {

    @GET
    public Response get(@Context Configuration configuration) {
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return JaxRs.response(target.get().transport().resourceMetadata(target.get().server()));
    }
}
