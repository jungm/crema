package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.internal.cdi.CremaDeployment;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Adapts {@link McpTransport} to JAX-RS. Request bodies are read as raw bytes, and responses are written straight
 * to the servlet response and committed, so no JAX-RS provider of the application can change the wire format: on
 * TomEE, the providers an application's own scanning {@code Application} discovers apply to every
 * {@code Application} of the WAR, including the {@code McpApplication}s.
 */
final class JaxRs {

    private JaxRs() {
    }

    /**
     * The transport and MCP Server of the {@code McpApplication} a request belongs to; empty when the application
     * serving the request isn't an {@code McpApplication}.
     */
    static Optional<Target> target(Configuration configuration) {
        if (!(configuration.getProperty(McpApplication.APPLICATION_PROPERTY) instanceof Class<?> application)) {
            return Optional.empty();
        }
        return CremaDeployment.transport()
                .flatMap(transport -> transport.server(application).map(server -> new Target(transport, server)));
    }

    record Target(McpTransport transport, McpServerModel server) {
    }

    /**
     * Case-insensitive header lookup.
     */
    static Function<String, List<String>> headers(MultivaluedMap<String, String> headers) {
        return name -> {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                    values.addAll(entry.getValue());
                }
            }
            return values;
        };
    }

    static String first(Function<String, List<String>> headers, String name) {
        List<String> values = headers.apply(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * The caller according to the Runtime. The security context is consulted only when a policy asks.
     */
    static Caller caller(SecurityContext security, Function<String, List<String>> headers) {
        return new Caller() {
            @Override
            public List<String> header(String name) {
                return headers.apply(name);
            }

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
        servletResponse.setHeader(McpEndpoint.X_ACCEL_BUFFERING, "no");
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
