package io.github.jungm.crema.internal.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.crema.internal.invoke.ProgressChannel;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.protocol.McpError;
import io.github.jungm.crema.internal.protocol.Request;
import io.github.jungm.crema.internal.security.AccessPolicy;
import io.github.jungm.crema.internal.security.AccessPolicy.RejectedException;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonObject;

/**
 * Streamable HTTP for the MCP Servers of one application, independent of JAX-RS. Every request to an MCP Endpoint
 * passes the {@code Origin} check and then authentication; a {@code POST} is then validated and answered either
 * with one JSON response or, when it invokes a Feature Method that reports progress to a client that asked for it,
 * with an SSE stream. JSON-RPC errors use HTTP {@code 200} unless the spec names a status.
 */
public final class McpTransport {

    private static final Logger LOG = Logger.getLogger(McpTransport.class.getName());

    private final ServerRegistry registry;
    private final Dispatcher dispatcher;
    private final OriginPolicy origins;

    public McpTransport(ServerRegistry registry, Dispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.origins = new OriginPolicy(registry.settings().allowedOrigins());
    }

    /**
     * The MCP Server an {@code McpApplication} subclass declares.
     */
    public Optional<McpServerModel> server(Class<?> application) {
        return registry.server(application);
    }

    /**
     * The checks every request to an MCP Endpoint passes, whatever its HTTP method.
     *
     * @param origin the {@code Origin} header, or {@code null}
     * @return the response that rejects the request, or empty to proceed
     */
    public Optional<HttpReply> screen(McpServerModel server, String origin, Caller caller) {
        if (!origins.permits(origin)) {
            return Optional.of(HttpReply.json(403, Json.write(Dispatcher.error(null,
                    new McpError(McpError.INVALID_REQUEST, "Origin not allowed: " + origin, null, 403)).message())));
        }
        return dispatcher.services().access().authenticate(server, caller).map(McpTransport::reply);
    }

    /**
     * How to answer a {@code POST} that passed {@link #screen}.
     */
    public sealed interface Plan {
    }

    /**
     * Answer with this response.
     */
    public record Reply(HttpReply reply) implements Plan {
    }

    /**
     * Answer the request with one JSON response ({@link #respond}).
     */
    public record Respond(Request request) implements Plan {
    }

    /**
     * Answer the request with an SSE stream ({@link #stream}).
     */
    public record Stream(Request request) implements Plan {
    }

    /**
     * Validates a {@code POST} body and its headers.
     *
     * @param headers the values of a request header by case-insensitive name
     */
    public Plan plan(McpServerModel server, byte[] body, Function<String, List<String>> headers, Caller caller) {
        RequestValidator.Result result = RequestValidator.validate(body, headers);
        if (result instanceof RequestValidator.Rejected rejected) {
            return new Reply(reply(rejected.response()));
        }
        if (result instanceof RequestValidator.Notification) {
            return new Reply(new HttpReply(202, Map.of(), null));
        }
        Request request = ((RequestValidator.Accepted) result).request();
        try {
            return dispatcher.streams(server, request, caller) ? new Stream(request) : new Respond(request);
        } catch (RejectedException e) {
            return new Reply(reply(e.rejection()));
        }
    }

    /**
     * Handles a request and answers it with one JSON response.
     */
    public HttpReply respond(McpServerModel server, Request request, Caller caller) {
        try {
            return reply(dispatcher.handle(server, request, caller, ProgressChannel.NONE));
        } catch (RejectedException e) {
            return reply(e.rejection());
        }
    }

    /**
     * Handles a request on the current thread, writing its progress notifications and then its response to an SSE
     * stream, which is closed afterwards.
     */
    public void stream(McpServerModel server, Request request, Caller caller, EventStream events) {
        StreamChannel channel = new StreamChannel(events);
        JsonObject response;
        try {
            response = dispatcher.handle(server, request, caller, channel).message();
        } catch (RejectedException e) {
            response = Dispatcher.error(request.id(), McpError.internal("Forbidden")).message();
        }
        channel.finish(response);
    }

    /**
     * An SSE response stream: each event carries one JSON-RPC message.
     */
    public interface EventStream {

        /**
         * Sends one event whose {@code data} is {@code json}.
         */
        CompletionStage<?> send(String json);

        void close();
    }

    private static HttpReply reply(Dispatcher.Response response) {
        return HttpReply.json(response.status(), Json.write(response.message()));
    }

    private static HttpReply reply(AccessPolicy.Rejection rejection) {
        return new HttpReply(rejection.status(), rejection.headers(), null);
    }

    /**
     * Writes the messages of one request to its SSE stream. Once the final response is written, or the client has
     * gone away, further progress notifications are dropped.
     */
    private static final class StreamChannel implements ProgressChannel {

        private final EventStream events;
        private final AtomicBoolean closed = new AtomicBoolean();

        StreamChannel(EventStream events) {
            this.events = events;
        }

        @Override
        public synchronized CompletableFuture<Void> send(JsonObject notification) {
            if (closed.get()) {
                return CompletableFuture.completedFuture(null);
            }
            try {
                return events.send(Json.write(notification)).toCompletableFuture()
                        .handle((ignored, failure) -> {
                            if (failure != null) {
                                markClosed(failure);
                            }
                            return null;
                        });
            } catch (RuntimeException e) {
                markClosed(e);
                return CompletableFuture.completedFuture(null);
            }
        }

        void finish(JsonObject response) {
            CompletionStage<?> sent = null;
            synchronized (this) {
                if (closed.compareAndSet(false, true)) {
                    try {
                        sent = events.send(Json.write(response));
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "Couldn't send the final response; the client has gone away", e);
                    }
                }
            }
            try {
                if (sent != null) {
                    sent.toCompletableFuture().join();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Couldn't send the final response; the client has gone away", e);
            } finally {
                try {
                    events.close();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Couldn't close the SSE stream", e);
                }
            }
        }

        private void markClosed(Throwable failure) {
            if (closed.compareAndSet(false, true)) {
                LOG.log(Level.FINE, "Couldn't send a progress notification; the client has gone away", failure);
            }
        }
    }
}
