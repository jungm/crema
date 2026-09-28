package io.github.jungm.crema.it.mcp;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One HTTP exchange with an MCP Endpoint. Every JSON-RPC message in the response, whether a JSON body or SSE
 * events, has been checked against the MCP JSON Schema when the exchange is created.
 */
public final class Exchange {

    private final String method;
    private final HttpResponse<String> response;
    private final List<JsonObject> messages = new ArrayList<>();

    Exchange(String method, HttpResponse<String> response) {
        this.method = method;
        this.response = response;
        String body = response.body();
        if (isEventStream()) {
            for (String data : events(body)) {
                messages.add(parse(data));
            }
        } else if (body != null && !body.isEmpty() && contentType().startsWith("application/json")) {
            messages.add(parse(body));
        }
    }

    private JsonObject parse(String json) {
        List<String> violations = WireSchema.get().violations(json, method);
        if (!violations.isEmpty()) {
            fail("Message violates the MCP 2026-07-28 schema: " + violations + "\n" + json);
        }
        assertFalse(json.strip().contains("\n"), () -> "JSON must be written on one line: " + json);
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    /** The {@code data} of each SSE event. */
    private static List<String> events(String body) {
        List<String> events = new ArrayList<>();
        StringBuilder data = null;
        for (String line : body.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                if (data != null) {
                    events.add(data.toString());
                    data = null;
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                value = value.startsWith(" ") ? value.substring(1) : value;
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }
        if (data != null) {
            events.add(data.toString());
        }
        return events;
    }

    public int status() {
        return response.statusCode();
    }

    public String body() {
        return response.body();
    }

    public Optional<String> header(String name) {
        return response.headers().firstValue(name);
    }

    public String contentType() {
        return header("Content-Type").orElse("");
    }

    public boolean isEventStream() {
        return contentType().startsWith("text/event-stream");
    }

    public HttpResponse<String> response() {
        return response;
    }

    /** All JSON-RPC messages the server sent: one for JSON, each event for SSE. */
    public List<JsonObject> messages() {
        return List.copyOf(messages);
    }

    /** The JSON-RPC response: the only message of a JSON response, or the last event of a stream. */
    public JsonObject message() {
        assertFalse(messages.isEmpty(), () -> "No JSON-RPC message; HTTP " + status() + " " + contentType() + ": "
                + body());
        return messages.get(messages.size() - 1);
    }

    /** Asserts HTTP 200 and a result, and returns the result. */
    public JsonObject result() {
        assertEquals(200, status(), this::describe);
        JsonObject message = message();
        assertTrue(message.containsKey("result"), this::describe);
        return message.getJsonObject("result");
    }

    /** Asserts a JSON-RPC error with this HTTP status and code, and returns the error. */
    public JsonObject error(int httpStatus, int code) {
        assertEquals(httpStatus, status(), this::describe);
        JsonObject message = message();
        JsonObject error = message.getJsonObject("error");
        assertNotNull(error, this::describe);
        assertEquals(code, error.getInt("code"), this::describe);
        return error;
    }

    public String describe() {
        return "HTTP " + status() + " " + contentType() + ": " + body();
    }
}
