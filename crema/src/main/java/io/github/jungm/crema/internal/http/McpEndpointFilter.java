package io.github.jungm.crema.internal.http;

import java.io.ByteArrayInputStream;
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
 * Screens every request to the MCP Endpoint before resource matching ({@code Origin}, authentication) and validates
 * {@code POST}s, answering invalid ones itself. For a valid {@code POST} it sets {@code Accept} to
 * {@code text/event-stream} or {@code application/json}, which selects the {@link McpEndpoint} method that
 * streams or responds with JSON.
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
            return;
        }
        byte[] body = request.getEntityStream().readAllBytes();
        request.setEntityStream(new ByteArrayInputStream(body));
        McpTransport.Plan plan = transport.plan(target.get().server(), body, headers, caller);
        if (plan instanceof McpTransport.Reply reply) {
            request.abortWith(JaxRs.response(reply.reply()));
        } else {
            accept(request.getHeaders(), plan instanceof McpTransport.Stream ? MediaType.SERVER_SENT_EVENTS
                    : MediaType.APPLICATION_JSON);
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (MediaType.SERVER_SENT_EVENTS_TYPE.isCompatible(response.getMediaType())) {
            response.getHeaders().putSingle(McpEndpoint.X_ACCEL_BUFFERING, "no");
        }
    }

    private static void accept(MultivaluedMap<String, String> headers, String mediaType) {
        headers.keySet().removeIf(HttpHeaders.ACCEPT::equalsIgnoreCase);
        headers.putSingle(HttpHeaders.ACCEPT, mediaType);
    }
}
