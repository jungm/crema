package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.jungm.crema.internal.cdi.CremaDeployment;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Adapts {@link McpTransport} to JAX-RS and the servlet response. Responses are written straight to the servlet
 * response and committed, so no JAX-RS provider of the application can change the wire format: on TomEE, the
 * providers an application's own scanning {@code Application} discovers apply to every {@code Application} of the
 * WAR, including the {@code McpApplication}s.
 */
final class JaxRs {

    private static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";

    private JaxRs() {
    }

    /**
     * The transport and MCP Server of the {@code McpApplication} a request belongs to; empty when the application
     * serving the request isn't an {@code McpApplication}, or its MCP Servers aren't deployed.
     */
    static Optional<Target> target(Configuration configuration) {
        if (!(configuration.getProperty(McpEndpointFilter.APPLICATION_PROPERTY) instanceof Class<?> application)) {
            return Optional.empty();
        }
        return CremaDeployment.transport()
                .flatMap(transport -> transport.server(application).map(server -> new Target(transport, server)));
    }

    record Target(McpTransport transport, McpServerModel server) {
    }

    /**
     * The caller according to the Runtime, whose security context is consulted only when needed.
     */
    static Caller caller(SecurityContext security) {
        return new Caller() {
            @Override
            public Principal principal() {
                return security == null ? null : security.getUserPrincipal();
            }

            @Override
            public boolean isUserInRole(String role) {
                return security != null && security.isUserInRole(role);
            }
        };
    }

    /**
     * Writes a reply to the servlet response and commits it.
     */
    static void write(HttpServletResponse servletResponse, HttpReply reply) throws IOException {
        servletResponse.setStatus(reply.status());
        reply.headers().forEach(servletResponse::setHeader);
        if (reply.body() != null) {
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            servletResponse.setContentType(MediaType.APPLICATION_JSON);
            servletResponse.setContentLength(body.length);
            servletResponse.getOutputStream().write(body);
        }
        servletResponse.flushBuffer();
    }

    /**
     * An SSE stream written to the servlet response, one {@code data:} line per event. The response is committed
     * before the first event; a failed write means that the client has gone away.
     */
    static McpTransport.EventStream eventStream(HttpServletResponse servletResponse) throws IOException {
        servletResponse.setStatus(200);
        servletResponse.setContentType(MediaType.SERVER_SENT_EVENTS);
        servletResponse.setHeader("Cache-Control", "no-cache");
        servletResponse.setHeader(X_ACCEL_BUFFERING, "no");
        servletResponse.flushBuffer();
        OutputStream out = servletResponse.getOutputStream();
        return new McpTransport.EventStream() {
            @Override
            public CompletionStage<?> send(String json) {
                try {
                    out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return CompletableFuture.completedFuture(null);
                } catch (IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            @Override
            public void close() {
                try {
                    out.flush();
                } catch (IOException e) {
                    // the client has gone away, so there is nothing left to tell it
                }
            }
        };
    }
}
