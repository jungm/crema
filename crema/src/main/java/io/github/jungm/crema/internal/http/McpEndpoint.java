package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import io.github.jungm.crema.internal.protocol.Request;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * The MCP Endpoint. {@link McpEndpointFilter} decides whether a {@code POST} is answered with JSON or with an SSE
 * stream and sets the {@code Accept} header accordingly, which selects the resource method. Outside an
 * {@code McpApplication} this resource answers {@code 404}.
 */
@Path("")
public class McpEndpoint {

    static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response post(InputStream body, @Context HttpHeaders httpHeaders, @Context SecurityContext security,
            @Context UriInfo uriInfo, @Context Configuration configuration) throws IOException {
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        McpTransport transport = target.get().transport();
        Function<String, List<String>> headers = JaxRs.headers(httpHeaders.getRequestHeaders());
        McpTransport.Screening screening = transport.screen(target.get().server(), JaxRs.first(headers, "Origin"),
                JaxRs.caller(security, headers, uriInfo));
        if (screening instanceof McpTransport.Reply rejected) {
            return JaxRs.response(rejected.reply());
        }
        Caller caller = ((McpTransport.Admitted) screening).caller();
        McpTransport.Plan plan = transport.plan(target.get().server(), body.readAllBytes(), headers, caller);
        if (plan instanceof McpTransport.Reply reply) {
            return JaxRs.response(reply.reply());
        }
        return JaxRs.response(transport.respond(target.get().server(), request(plan), caller));
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(InputStream body, @Context HttpHeaders httpHeaders, @Context SecurityContext security,
            @Context UriInfo uriInfo, @Context Configuration configuration,
            @Context HttpServletResponse servletResponse,
            @Context SseEventSink sink, @Context Sse sse) throws IOException {
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            sink.close();
            return;
        }
        McpTransport transport = target.get().transport();
        Function<String, List<String>> headers = JaxRs.headers(httpHeaders.getRequestHeaders());
        McpTransport.Screening screening = transport.screen(target.get().server(), JaxRs.first(headers, "Origin"),
                JaxRs.caller(security, headers, uriInfo));
        Caller caller = screening instanceof McpTransport.Admitted admitted ? admitted.caller() : null;
        McpTransport.Plan plan = screening instanceof McpTransport.Reply rejected ? rejected
                : plan(transport, target.get(), body, headers, caller);
        McpTransport.EventStream events = new McpTransport.EventStream() {
            @Override
            public CompletionStage<?> send(String json) {
                return sink.send(sse.newEvent(json));
            }

            @Override
            public void close() {
                sink.close();
            }
        };
        if (plan instanceof McpTransport.Reply reply) {
            if (reply.reply().body() != null) {
                events.send(reply.reply().body());
            }
            events.close();
            return;
        }
        if (servletResponse != null) {
            servletResponse.setHeader(X_ACCEL_BUFFERING, "no");
        }
        transport.stream(target.get().server(), request(plan), caller, events);
    }

    @GET
    public Response get(@Context HttpHeaders httpHeaders, @Context SecurityContext security,
            @Context UriInfo uriInfo, @Context Configuration configuration) {
        return methodNotAllowed(httpHeaders, security, uriInfo, configuration);
    }

    @DELETE
    public Response delete(@Context HttpHeaders httpHeaders, @Context SecurityContext security,
            @Context UriInfo uriInfo, @Context Configuration configuration) {
        return methodNotAllowed(httpHeaders, security, uriInfo, configuration);
    }

    private static Response methodNotAllowed(HttpHeaders httpHeaders, SecurityContext security, UriInfo uriInfo,
            Configuration configuration) {
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Function<String, List<String>> headers = JaxRs.headers(httpHeaders.getRequestHeaders());
        McpTransport.Screening screening = target.get().transport().screen(target.get().server(),
                JaxRs.first(headers, "Origin"), JaxRs.caller(security, headers, uriInfo));
        return JaxRs.response(screening instanceof McpTransport.Reply rejected ? rejected.reply()
                : new HttpReply(405, Map.of(HttpHeaders.ALLOW, "POST"), null));
    }

    private static McpTransport.Plan plan(McpTransport transport, JaxRs.Target target, InputStream body,
            Function<String, List<String>> headers, Caller caller) {
        try {
            return transport.plan(target.server(), body.readAllBytes(), headers, caller);
        } catch (IOException e) {
            return new McpTransport.Reply(new HttpReply(400, Map.of(), null));
        }
    }

    private static Request request(McpTransport.Plan plan) {
        return plan instanceof McpTransport.Stream stream ? stream.request()
                : ((McpTransport.Respond) plan).request();
    }
}
