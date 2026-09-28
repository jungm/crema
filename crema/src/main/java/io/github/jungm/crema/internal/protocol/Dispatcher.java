package io.github.jungm.crema.internal.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.crema.internal.invoke.ProgressChannel;
import io.github.jungm.crema.internal.invoke.ProgressTokenImpl;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.security.AccessPolicy.RejectedException;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Handles validated requests: routes each MCP method to its handler and wraps the outcome in a JSON-RPC response.
 * Every result carries {@code resultType: "complete"} and the MCP Server's {@code serverInfo} in {@code _meta}.
 */
public final class Dispatcher {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    private static final Logger LOG = Logger.getLogger(Dispatcher.class.getName());

    private static final Map<String, Function<Call, JsonObject>> HANDLERS = Map.of(
            "server/discover", Listings::discover,
            "tools/list", Listings::tools,
            "tools/call", Invocations::callTool,
            "resources/list", Listings::resources,
            "resources/read", Invocations::readResource,
            "resources/templates/list", Listings::resourceTemplates,
            "prompts/list", Listings::prompts,
            "prompts/get", Invocations::getPrompt,
            "completion/complete", Invocations::complete);

    private final Services services;

    public Dispatcher(Services services) {
        this.services = services;
    }

    public Services services() {
        return services;
    }

    /**
     * Whether Crema implements an MCP method.
     */
    public static boolean isImplemented(String method) {
        return HANDLERS.containsKey(method);
    }

    /**
     * Whether a request is answered with an SSE stream: it invokes a Feature Method that takes {@link
     * org.mcpjava.server.progress.Progress} and carries a {@code progressToken}.
     *
     * @throws RejectedException if the caller may not use that Feature
     */
    public boolean streams(McpServerModel server, Request request, Caller caller) {
        if (ProgressTokenImpl.of(request.meta()).isEmpty()) {
            return false;
        }
        Optional<Feature> target = Invocations.target(call(server, request, caller, ProgressChannel.NONE));
        if (target.isEmpty() || !target.get().method().acceptsProgress()) {
            return false;
        }
        if (!services.access().permits(server, target.get(), caller)) {
            throw new RejectedException(services.access().forbidden(server, caller));
        }
        return true;
    }

    /**
     * Handles a request. JSON-RPC errors come back as the HTTP status and error response to send.
     *
     * @throws RejectedException if the request must be answered with a {@link
     *         io.github.jungm.crema.internal.security.AccessPolicy.Rejection}
     */
    public Response handle(McpServerModel server, Request request, Caller caller, ProgressChannel progress) {
        Function<Call, JsonObject> handler = HANDLERS.get(request.method());
        if (handler == null) {
            return error(request.id(), methodNotFound(request.method()));
        }
        try {
            JsonObject result = handler.apply(call(server, request, caller, progress));
            return new Response(200, envelope(request.id()).add("result", complete(result, server)).build());
        } catch (McpError e) {
            return error(request.id(), e);
        } catch (RejectedException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            if (e instanceof VirtualMachineError error) {
                throw error;
            }
            LOG.log(Level.WARNING, "Handling " + request.method() + " failed", e);
            return error(request.id(), McpError.internal("Internal error"));
        }
    }

    /**
     * A JSON-RPC response to send with an HTTP status.
     */
    public record Response(int status, JsonObject message) {
    }

    public static McpError methodNotFound(String method) {
        return new McpError(McpError.METHOD_NOT_FOUND, "Method not found: " + method, null, 404);
    }

    /**
     * The error response for a request, with the error's HTTP status.
     *
     * @param id the request id, or {@code null} if it couldn't be read
     */
    public static Response error(JsonValue id, McpError error) {
        JsonObjectBuilder body = Json.object().add("code", error.code()).add("message", String.valueOf(
                error.getMessage()));
        if (error.data() != null) {
            body.add("data", error.data());
        }
        return new Response(error.httpStatus(), envelope(id).add("error", body).build());
    }

    private Call call(McpServerModel server, Request request, Caller caller, ProgressChannel progress) {
        return new Call(server, request, caller, progress, services);
    }

    private static JsonObjectBuilder envelope(JsonValue id) {
        JsonObjectBuilder envelope = Json.object().add("jsonrpc", "2.0");
        if (id != null) {
            envelope.add("id", id);
        }
        return envelope;
    }

    private static JsonObject complete(JsonObject result, McpServerModel server) {
        JsonObjectBuilder meta = Json.object();
        if (result.get("_meta") instanceof JsonObject existing) {
            existing.forEach(meta::add);
        }
        meta.add(SERVER_INFO, ProtocolJson.implementation(server.info()));
        return Json.FACTORY.createObjectBuilder(result).add("resultType", "complete").add("_meta", meta).build();
    }
}
