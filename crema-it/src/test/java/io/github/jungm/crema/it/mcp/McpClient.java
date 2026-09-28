package io.github.jungm.crema.it.mcp;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal MCP {@code 2026-07-28} client over Streamable HTTP: sets the standard headers and the {@code _meta}
 * fields every request needs, and validates every JSON-RPC message the server sends against the MCP JSON Schema
 * ({@link WireSchema}). {@link Post} lets a test break any part of the request.
 */
public final class McpClient {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private final URI endpoint;
    private final AtomicInteger ids = new AtomicInteger();

    public McpClient(URI endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * A client for the MCP Endpoint at {@code path} below the deployment URL.
     */
    public static McpClient at(URL deployment, String path) {
        String root = deployment.toExternalForm();
        return new McpClient(URI.create(root + (root.endsWith("/") ? "" : "/") + path));
    }

    public URI endpoint() {
        return endpoint;
    }

    /**
     * Sends a well-formed request and returns the exchange.
     */
    public Exchange request(String method, JsonObject params) {
        return post(method).params(params).send();
    }

    public Exchange request(String method) {
        return post(method).send();
    }

    /** {@code tools/call} with the given arguments, or none if {@code null}. */
    public Exchange callTool(String name, JsonObject arguments) {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", name);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return request("tools/call", params.build());
    }

    public Exchange readResource(String uri) {
        return request("resources/read", Json.createObjectBuilder().add("uri", uri).build());
    }

    public Exchange getPrompt(String name, JsonObject arguments) {
        JsonObjectBuilder params = Json.createObjectBuilder().add("name", name);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return request("prompts/get", params.build());
    }

    /**
     * A request to adjust before {@link Post#send() sending}.
     */
    public Post post(String method) {
        return new Post(method);
    }

    /**
     * Sends a request with another HTTP method and no body.
     */
    public HttpResponse<String> send(String httpMethod, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .method(httpMethod, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The value of a header in the Base64 sentinel form {@code =?base64?...?=}.
     */
    public static String sentinel(String value) {
        return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /**
     * One request, built from well-formed defaults.
     */
    public final class Post {

        private final String method;
        private JsonValue id;
        private JsonObject params = JsonValue.EMPTY_JSON_OBJECT;
        private final Map<String, JsonValue> meta = new LinkedHashMap<>();
        private boolean withMeta = true;
        private boolean withParams = true;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String rawBody;

        private Post(String method) {
            this.method = method;
            this.id = Json.createValue(ids.incrementAndGet());
            meta.put(META_PROTOCOL_VERSION, Json.createValue(PROTOCOL_VERSION));
            meta.put(META_CLIENT_CAPABILITIES, JsonValue.EMPTY_JSON_OBJECT);
            meta.put(META_CLIENT_INFO, Json.createObjectBuilder().add("name", "crema-it").add("version", "1.0.0")
                    .build());
            headers.put("Accept", "application/json, text/event-stream");
            headers.put("Content-Type", "application/json");
            headers.put("MCP-Protocol-Version", PROTOCOL_VERSION);
            headers.put("Mcp-Method", method);
        }

        /** The request's {@code params} besides {@code _meta}. */
        public Post params(JsonObject params) {
            this.params = params;
            return this;
        }

        public Post id(JsonValue id) {
            this.id = id;
            return this;
        }

        /** Sends a notification: no {@code id}. */
        public Post notification() {
            this.id = null;
            return this;
        }

        /** Sets a {@code _meta} entry, or removes it if {@code value} is {@code null}. */
        public Post meta(String key, JsonValue value) {
            if (value == null) {
                meta.remove(key);
            } else {
                meta.put(key, value);
            }
            return this;
        }

        public Post meta(String key, String value) {
            return meta(key, value == null ? null : Json.createValue(value));
        }

        public Post withoutMeta() {
            this.withMeta = false;
            return this;
        }

        public Post withoutParams() {
            this.withParams = false;
            return this;
        }

        /** Sets a header, or removes it if {@code value} is {@code null}. */
        public Post header(String name, String value) {
            headers.keySet().removeIf(name::equalsIgnoreCase);
            if (value != null) {
                headers.put(name, value);
            }
            return this;
        }

        /** Sends this body verbatim instead of the JSON-RPC request. */
        public Post body(String body) {
            this.rawBody = body;
            return this;
        }

        public JsonObject message() {
            JsonObjectBuilder message = Json.createObjectBuilder().add("jsonrpc", "2.0");
            if (id != null) {
                message.add("id", id);
            }
            message.add("method", method);
            if (withParams) {
                JsonObjectBuilder p = Json.createObjectBuilder(params);
                if (withMeta) {
                    JsonObjectBuilder m = Json.createObjectBuilder();
                    meta.forEach(m::add);
                    p.add("_meta", m);
                }
                message.add("params", p);
            }
            return message.build();
        }

        public Exchange send() {
            if (rawBody == null && !headers.keySet().stream().anyMatch("Mcp-Name"::equalsIgnoreCase)) {
                String name = switch (method) {
                    case "tools/call", "prompts/get" -> params.containsKey("name") ? params.getString("name") : null;
                    case "resources/read" -> params.containsKey("uri") ? params.getString("uri") : null;
                    default -> null;
                };
                if (name != null) {
                    headers.put("Mcp-Name", name);
                }
            }
            String body = rawBody != null ? rawBody : message().toString();
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            try {
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Exchange(method, response);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
