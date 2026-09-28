package io.github.jungm.crema.internal.http;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.internal.cdi.CremaDeployment;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.security.Caller;
import io.github.jungm.crema.internal.security.TokenSecurityContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Adapts {@link McpTransport} to JAX-RS. Bodies are read and written as raw bytes, so no application provider can
 * change the wire format.
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
     * The caller of a request: the bearer token caller if {@link McpEndpointFilter} has admitted one, else the
     * caller according to the Runtime, whose security context is consulted only when a policy asks.
     */
    static Caller caller(SecurityContext security, Function<String, List<String>> headers) {
        Optional<Caller> token = TokenSecurityContext.caller(security);
        if (token.isPresent()) {
            return token.get();
        }
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

    static Response response(HttpReply reply) {
        Response.ResponseBuilder response = Response.status(reply.status());
        reply.headers().forEach(response::header);
        if (reply.body() != null) {
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            response.entity((StreamingOutput) out -> out.write(body)).type(MediaType.APPLICATION_JSON_TYPE);
        }
        return response.build();
    }
}
