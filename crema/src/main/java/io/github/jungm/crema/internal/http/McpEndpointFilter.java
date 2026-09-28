package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jungm.crema.internal.security.Caller;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Handles every request to the MCP Endpoint before resource matching: the {@code Origin} check and
 * authentication, {@code 405} for anything but {@code POST}, and reading and validating a {@code POST}, answering
 * invalid ones itself. A valid {@code POST} is handed to {@link McpEndpoint} as a request property, with
 * {@code Content-Type} set to {@link McpEndpoint#PLANNED} and {@code Accept} set to {@code text/event-stream} or
 * {@code application/json}, which selects the resource method that streams or responds with JSON. Only
 * {@code McpApplication}s register this filter.
 */
@PreMatching
public class McpEndpointFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Context
    private Configuration configuration;

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        String path = request.getUriInfo().getPath();
        if (!path.isEmpty() && !path.equals("/")) {
            return;
        }
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            request.abortWith(JaxRs.response(new HttpReply(503, Map.of(), null)));
            return;
        }
        McpTransport transport = target.get().transport();
        Function<String, List<String>> headers = JaxRs.headers(request.getHeaders());
        Caller caller = JaxRs.caller(request.getSecurityContext(), headers);
        Optional<HttpReply> rejected = transport.screen(target.get().server(), JaxRs.first(headers, "Origin"),
                caller);
        if (rejected.isPresent()) {
            request.abortWith(JaxRs.response(rejected.get()));
            return;
        }
        if (!HttpMethod.POST.equals(request.getMethod())) {
            request.abortWith(JaxRs.response(new HttpReply(405, Map.of(HttpHeaders.ALLOW, HttpMethod.POST), null)));
            return;
        }
        McpTransport.Plan plan = transport.plan(target.get().server(), request.getEntityStream(),
                request.getLength(), headers, caller);
        if (plan instanceof McpTransport.Reply reply) {
            request.abortWith(JaxRs.response(reply.reply()));
            return;
        }
        request.setProperty(McpEndpoint.PLAN_PROPERTY, new McpEndpoint.Planned(transport, target.get().server(),
                plan, caller));
        MultivaluedMap<String, String> requestHeaders = request.getHeaders();
        replace(requestHeaders, HttpHeaders.CONTENT_TYPE, McpEndpoint.PLANNED);
        replace(requestHeaders, HttpHeaders.ACCEPT, plan instanceof McpTransport.Stream
                ? MediaType.SERVER_SENT_EVENTS : MediaType.APPLICATION_JSON);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (MediaType.SERVER_SENT_EVENTS_TYPE.isCompatible(response.getMediaType())) {
            response.getHeaders().putSingle(McpEndpoint.X_ACCEL_BUFFERING, "no");
        }
    }

    private static void replace(MultivaluedMap<String, String> headers, String name, String value) {
        headers.keySet().removeIf(name::equalsIgnoreCase);
        headers.putSingle(name, value);
    }
}
