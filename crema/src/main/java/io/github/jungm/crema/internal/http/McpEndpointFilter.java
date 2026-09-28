package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * Handles every request to the MCP Endpoint before resource matching: the {@code Origin} check and
 * authentication, {@code 405} for anything but {@code POST}, and reading and validating a {@code POST}. It answers
 * invalid requests itself, writing to the servlet response directly (see {@link JaxRs#write}). A valid
 * {@code POST} is handed to {@link McpEndpoint} as a request property, with {@code Content-Type} set to
 * {@link McpEndpoint#PLANNED}, which only {@code McpEndpoint} consumes. Only {@code McpApplication}s register this
 * filter.
 */
@PreMatching
public class McpEndpointFilter implements ContainerRequestFilter {

    @Context
    private Configuration configuration;

    @Context
    private HttpServletResponse servletResponse;

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        String path = request.getUriInfo().getPath();
        if (!path.isEmpty() && !path.equals("/")) {
            return;
        }
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            abort(request, new HttpReply(503, Map.of(), null));
            return;
        }
        McpTransport transport = target.get().transport();
        Function<String, List<String>> headers = JaxRs.headers(request.getHeaders());
        Caller caller = JaxRs.caller(request.getSecurityContext(), headers);
        Optional<HttpReply> rejected = transport.screen(target.get().server(), JaxRs.first(headers, "Origin"),
                caller);
        if (rejected.isPresent()) {
            abort(request, rejected.get());
            return;
        }
        if (!HttpMethod.POST.equals(request.getMethod())) {
            abort(request, new HttpReply(405, Map.of(HttpHeaders.ALLOW, HttpMethod.POST), null));
            return;
        }
        McpTransport.Plan plan = transport.plan(target.get().server(), request.getEntityStream(),
                request.getLength(), headers, caller);
        if (plan instanceof McpTransport.Reply reply) {
            abort(request, reply.reply());
            return;
        }
        request.setProperty(McpEndpoint.PLAN_PROPERTY, new McpEndpoint.Planned(transport, target.get().server(),
                plan, caller));
        MultivaluedMap<String, String> requestHeaders = request.getHeaders();
        requestHeaders.keySet().removeIf(HttpHeaders.CONTENT_TYPE::equalsIgnoreCase);
        requestHeaders.putSingle(HttpHeaders.CONTENT_TYPE, McpEndpoint.PLANNED);
    }

    private void abort(ContainerRequestContext request, HttpReply reply) throws IOException {
        JaxRs.write(servletResponse, reply);
        request.abortWith(Response.status(reply.status()).build());
    }
}
