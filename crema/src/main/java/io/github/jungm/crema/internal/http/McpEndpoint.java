package io.github.jungm.crema.internal.http;

import java.io.IOException;

import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Request;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;

/**
 * The MCP Endpoint. It executes what {@link McpEndpointFilter} has validated and planned: the filter passes the
 * plan as a request property and sets {@code Content-Type} to {@link #PLANNED}. The response, JSON or an SSE
 * stream, is written to the servlet response directly (see {@link JaxRs#write}).
 * <p>
 * An application's own scanning {@code Application} may pick this class up as well. There it has no {@code GET}
 * method, and its only method consumes {@link #PLANNED}, a media type no client sends, so it never competes with
 * the application's own root resource. Without a plan it answers {@code 404}.
 */
@Path("")
public class McpEndpoint {

    /**
     * The request {@code Content-Type} that {@link McpEndpointFilter} sets for a planned request.
     */
    public static final String PLANNED = "application/x-crema-planned+json";

    static final String PLAN_PROPERTY = "io.github.jungm.crema.plan";
    static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";

    /**
     * A validated request and what is needed to handle it.
     */
    record Planned(McpTransport transport, McpServerModel server, McpTransport.Plan plan, Caller caller) {
    }

    @POST
    @Consumes(PLANNED)
    public void post(@Context HttpServletRequest servletRequest, @Context HttpServletResponse servletResponse)
            throws IOException {
        if (!(servletRequest.getAttribute(PLAN_PROPERTY) instanceof Planned planned)) {
            throw new NotFoundException();
        }
        if (planned.plan() instanceof McpTransport.Stream stream) {
            planned.transport().stream(planned.server(), stream.request(), planned.caller(),
                    JaxRs.eventStream(servletResponse));
        } else {
            Request request = ((McpTransport.Respond) planned.plan()).request();
            JaxRs.write(servletResponse, planned.transport().respond(planned.server(), request, planned.caller()));
        }
    }
}
