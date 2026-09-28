package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.github.jungm.crema.internal.model.McpServerModel;
import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Serves every request to an {@code McpApplication}, before resource matching and ahead of any other request
 * filter:
 * <ul>
 * <li>the MCP Endpoint, the application path itself, through {@link McpTransport}: the checks, reading and
 * validating the request, and executing it, with one JSON response or an SSE stream;</li>
 * <li>{@code <MCP Endpoint>/.well-known/oauth-protected-resource}, the Protected Resource Metadata;</li>
 * <li>{@code 404} for any other path.</li>
 * </ul>
 * The response is written straight to the servlet response and committed (see {@link JaxRs#write}), and the
 * request is aborted, so no resource method, post-matching filter, exception mapper or message body writer runs
 * for it, whether Crema's or the application's. The CDI request context is active throughout, since the servlet
 * request is. Only {@code McpApplication}s register this filter.
 */
@PreMatching
@Priority(Integer.MIN_VALUE)
public class McpEndpointFilter implements ContainerRequestFilter {

    /**
     * The {@code Application} property that holds the {@code McpApplication} subclass serving a request.
     */
    public static final String APPLICATION_PROPERTY = "io.github.jungm.crema.application";

    private static final List<String> METADATA_PATH = List.of(".well-known", "oauth-protected-resource");

    @Context
    private Configuration configuration;

    @Context
    private HttpServletResponse servletResponse;

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        Optional<JaxRs.Target> target = JaxRs.target(configuration);
        if (target.isEmpty()) {
            answer(request, HttpReply.empty(503));
            return;
        }
        McpTransport transport = target.get().transport();
        McpServerModel server = target.get().server();
        List<String> path = path(request.getUriInfo());
        if (path.isEmpty()) {
            McpTransport.Outcome outcome = transport.handle(server, new HttpRequest(request.getMethod(),
                    Headers.of(request.getHeaders()), request.getLength(), request.getEntityStream()),
                    JaxRs.caller(request.getSecurityContext()));
            if (outcome instanceof McpTransport.Stream stream) {
                stream.writeTo(JaxRs.eventStream(servletResponse));
                request.abortWith(Response.ok().build());
            } else {
                answer(request, (HttpReply) outcome);
            }
        } else if (path.equals(METADATA_PATH)) {
            answer(request, transport.resourceMetadata(server, request.getMethod()));
        } else {
            answer(request, HttpReply.empty(404));
        }
    }

    /**
     * The path of a request below the application path, without empty segments: empty for the MCP Endpoint
     * itself, whatever matrix parameters it carries ({@code /mcp}, {@code /mcp/}, {@code /mcp/;x=1}).
     */
    static List<String> path(UriInfo uriInfo) {
        return uriInfo.getPathSegments().stream().map(PathSegment::getPath).filter(segment -> !segment.isEmpty())
                .toList();
    }

    private void answer(ContainerRequestContext request, HttpReply reply) throws IOException {
        JaxRs.write(servletResponse, reply);
        request.abortWith(Response.status(reply.status()).build());
    }
}
