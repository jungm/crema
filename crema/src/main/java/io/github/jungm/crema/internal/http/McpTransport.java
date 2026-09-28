package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.crema.internal.invoke.ProgressChannel;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.protocol.McpError;
import io.github.jungm.crema.internal.protocol.Rejection;
import io.github.jungm.crema.internal.protocol.Request;
import io.github.jungm.crema.internal.security.Caller;
import io.github.jungm.crema.internal.security.CremaAccessPolicy;
import jakarta.json.JsonObject;

/**
 * Streamable HTTP for the MCP Servers of one application, independent of JAX-RS. Every request to an MCP Endpoint
 * passes the {@code Origin} check and then authentication, whatever its HTTP method; a {@code POST} is then read,
 * validated and answered either with one JSON response or, when it invokes a Feature Method that reports progress
 * to a client that asked for it, with an SSE stream. JSON-RPC errors use HTTP {@code 200} unless the spec names a
 * status. A request that is rejected, for whatever reason, is answered with a {@link HttpReply}; nothing but a
 * {@link VirtualMachineError} escapes.
 */
public final class McpTransport {

    private static final Logger LOG = Logger.getLogger(McpTransport.class.getName());

    private static final String ORIGIN = "Origin";
    private static final String AUTHORIZATION = "Authorization";

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
     * How a request is answered: with one {@link HttpReply}, or with an SSE {@link Stream}.
     */
    public sealed interface Outcome permits HttpReply, Stream {
    }

    /**
     * Handles one request to the MCP Endpoint of an MCP Server. A {@code POST} body larger than
     * {@code crema.max-request-bytes}, by {@code Content-Length} or while reading, is answered with {@code 413}
     * and a JSON-RPC {@code -32600} error without {@code id}.
     *
     * @param caller the caller according to the Runtime
     * @throws IOException if the body can't be read
     */
    public Outcome handle(McpServerModel server, HttpRequest request, Caller caller) throws IOException {
        try {
            String origin = request.headers().first(ORIGIN);
            if (!origins.permits(origin)) {
                return HttpReply.error(new McpError(McpError.INVALID_REQUEST, "Origin not allowed: " + origin,
                        null, 403));
            }
            Caller admitted = access().authenticate(server, caller, request.headers().all(AUTHORIZATION));
            if (!"POST".equals(request.method())) {
                return HttpReply.empty(405).withHeader("Allow", "POST");
            }
            Optional<Request> validated = RequestValidator.validate(read(request), request.headers());
            if (validated.isEmpty()) {
                return HttpReply.empty(202);
            }
            return dispatch(server, validated.get(), admitted);
        } catch (Rejection e) {
            return HttpReply.of(e);
        }
    }

    /**
     * The Protected Resource Metadata (RFC 9728) of a protected MCP Server, for {@code GET}; {@code 404} for other
     * MCP Servers. It is served to anyone, without the {@code Origin} check.
     */
    public HttpReply resourceMetadata(McpServerModel server, String method) {
        Optional<JsonObject> metadata = access().resourceMetadata(server);
        if (metadata.isEmpty()) {
            return HttpReply.empty(404);
        }
        if (!"GET".equals(method)) {
            return HttpReply.empty(405).withHeader("Allow", "GET");
        }
        return new HttpReply(200, Map.of(), Json.write(metadata.get()));
    }

    private byte[] read(HttpRequest request) throws IOException {
        long max = registry.settings().maxRequestBytes();
        if (request.contentLength() > max) {
            throw tooLarge(max);
        }
        byte[] bytes = request.body().readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > max) {
            throw tooLarge(max);
        }
        return bytes;
    }

    private static Rejection tooLarge(long max) {
        return Rejection.of(null, new McpError(McpError.INVALID_REQUEST, "Request body exceeds " + max + " bytes",
                null, 413));
    }

    private Outcome dispatch(McpServerModel server, Request request, Caller caller) {
        try {
            if (dispatcher.streams(server, request, caller)) {
                return new Stream(server, request, caller);
            }
            return HttpReply.of(dispatcher.handle(server, request, caller, ProgressChannel.NONE));
        } catch (Rejection e) {
            return HttpReply.of(e);
        } catch (RuntimeException | Error e) {
            return HttpReply.of(lastResort(request, e));
        }
    }

    /**
     * The last resort for a failure that escaped the {@link Dispatcher}; the Runtime must never see it, since it
     * would answer with a stack trace or leave an SSE stream open.
     */
    private static Dispatcher.Response lastResort(Request request, Throwable failure) {
        return Dispatcher.error(request.id(), Dispatcher.internalError("Handling " + request.method(), failure));
    }

    private CremaAccessPolicy access() {
        return dispatcher.services().access();
    }

    /**
     * A request answered with an SSE stream. The caller's access to the Feature it invokes has been checked, so
     * handling it doesn't reject it once the stream has started.
     */
    public final class Stream implements Outcome {

        private final McpServerModel server;
        private final Request request;
        private final Caller caller;

        private Stream(McpServerModel server, Request request, Caller caller) {
            this.server = server;
            this.request = request;
            this.caller = caller;
        }

        /**
         * Handles the request on the current thread, writing its progress notifications and then its response
         * to {@code events}, which is closed afterwards.
         */
        public void writeTo(EventStream events) {
            StreamChannel channel = new StreamChannel(events);
            JsonObject response = null;
            try {
                response = dispatcher.handle(server, request, caller, channel).message();
            } catch (RuntimeException | Error e) {
                response = lastResort(request, e).message();
            } finally {
                channel.finish(response != null ? response
                        : Dispatcher.error(request.id(), McpError.internal("Internal error")).message());
            }
        }
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
