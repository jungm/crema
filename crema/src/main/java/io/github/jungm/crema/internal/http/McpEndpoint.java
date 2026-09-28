package io.github.jungm.crema.internal.http;

import java.util.concurrent.CompletionStage;

import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.protocol.Request;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * The MCP Endpoint. It executes what {@link McpEndpointFilter} has validated and planned: the filter passes the
 * plan as a request property, sets {@code Content-Type} to {@link #PLANNED}, and sets {@code Accept} to select the
 * method that responds with JSON or streams.
 * <p>
 * An application's own scanning {@code Application} may pick this class up as well. There it has no
 * {@code GET} method, and its methods consume only {@link #PLANNED}, a media type no client sends, so they never
 * compete with the application's own root resource. Without a plan it answers {@code 404}.
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

        Request request() {
            return plan instanceof McpTransport.Stream stream ? stream.request()
                    : ((McpTransport.Respond) plan).request();
        }
    }

    @POST
    @Consumes(PLANNED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response post(@Context HttpServletRequest servletRequest) {
        if (!(servletRequest.getAttribute(PLAN_PROPERTY) instanceof Planned planned)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return JaxRs.response(planned.transport().respond(planned.server(), planned.request(), planned.caller()));
    }

    @POST
    @Consumes(PLANNED)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context HttpServletRequest servletRequest, @Context HttpServletResponse servletResponse,
            @Context SseEventSink sink, @Context Sse sse) {
        if (!(servletRequest.getAttribute(PLAN_PROPERTY) instanceof Planned planned)) {
            sink.close();
            return;
        }
        if (servletResponse != null) {
            servletResponse.setHeader(X_ACCEL_BUFFERING, "no");
        }
        planned.transport().stream(planned.server(), planned.request(), planned.caller(),
                new McpTransport.EventStream() {
                    @Override
                    public CompletionStage<?> send(String json) {
                        return sink.send(sse.newEvent(json));
                    }

                    @Override
                    public void close() {
                        sink.close();
                    }
                });
    }
}
