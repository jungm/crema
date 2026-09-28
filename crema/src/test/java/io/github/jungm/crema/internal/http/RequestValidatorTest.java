package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.protocol.Rejection;
import io.github.jungm.crema.internal.protocol.Request;
import jakarta.json.JsonObject;

/**
 * The validation order of protocol-notes G6 and the header rules of the transport.
 */
class RequestValidatorTest {

    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"%s\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    @Test
    void validRequest() {
        Result result = validate(body(1, "tools/list", "2026-07-28", ""),
                headers("2026-07-28", "tools/list", null));
        Request accepted = accepted(result);
        assertEquals("tools/list", accepted.method());
        assertEquals(Json.parse("1"), accepted.id());
    }

    @Test
    void parseError() {
        assertRejected(400, -32700, null, validate("{\"jsonrpc\":", Map.of()));
        assertRejected(400, -32700, null, validate("", Map.of()));
    }

    @Test
    void invalidRequests() {
        assertRejected(400, -32600, null, validate("[" + body(1, "tools/list", "2026-07-28", "") + "]", Map.of()));
        assertRejected(400, -32600, null, validate("\"hello\"", Map.of()));
        assertRejected(400, -32600, "1", validate("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", Map.of()));
        assertRejected(400, -32600, "1", validate("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"x\"}", Map.of()));
        assertRejected(400, -32600, null, validate("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"x\"}", Map.of()));
        assertRejected(400, -32600, null, validate("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"x\"}", Map.of()));
    }

    @Test
    void notificationsAreAccepted() {
        notification(
                validate("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}", Map.of()));
    }

    @Test
    void missingHeadersComeBeforeMissingMeta() {
        String noMeta = "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\",\"params\":{}}";
        assertRejected(400, -32020, "\"a\"", validate(noMeta, Map.of("Mcp-Method", "tools/list")));
        assertRejected(400, -32020, "\"a\"", validate(noMeta, Map.of("MCP-Protocol-Version", "2026-07-28")));
    }

    @Test
    void missingMeta() {
        Map<String, String> headers = headers("2026-07-28", "tools/list", null);
        for (String params : List.of("", ",\"params\":[]", ",\"params\":{}", ",\"params\":{\"_meta\":{}}",
                ",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}",
                ",\"params\":{\"_meta\":{\"io.modelcontextprotocol/clientCapabilities\":{}}}")) {
            assertRejected(400, -32602, "3",
                    validate("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"" + params + "}", headers));
        }
    }

    @Test
    void headerMismatchComesBeforeVersionCheck() {
        assertRejected(400, -32020, "1", validate(body(1, "tools/list", "v999.0.0", ""),
                headers("2026-07-28", "tools/list", null)));
    }

    @Test
    void unsupportedVersion() {
        JsonObject error = assertRejected(400, -32022, "1", validate(body(1, "tools/list", "v999.0.0", ""),
                headers("v999.0.0", "tools/list", null)));
        assertEquals(Json.parse("{\"supported\":[\"2026-07-28\"],\"requested\":\"v999.0.0\"}"), error.get("data"));
    }

    @Test
    void unknownAndRemovedMethods() {
        for (String method : List.of("initialize", "ping", "logging/setLevel", "resources/subscribe",
                "resources/unsubscribe", "subscriptions/listen", "unknown/method")) {
            assertRejected(404, -32601, "1", validate(body(1, method, "2026-07-28", ""),
                    headers("2026-07-28", method, null)));
        }
    }

    @Test
    void legacyInitializeNamesTheSupportedVersion() {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"c\","
                + "\"version\":\"1\"}}}";
        JsonObject error = assertRejected(400, -32020, "0", validate(initialize, Map.of()));
        assertTrue(error.getString("message").contains("2026-07-28"), error.getString("message"));
    }

    @Test
    void methodHeaderIsCaseSensitive() {
        assertRejected(400, -32020, "1", validate(body(1, "tools/list", "2026-07-28", ""),
                headers("2026-07-28", "TOOLS/LIST", null)));
    }

    @Test
    void nameHeader() {
        String call = body(1, "tools/call", "2026-07-28", ",\"name\":\"echo\"");
        accepted(validate(call, headers("2026-07-28", "tools/call", "echo")));
        accepted(
                validate(call, headers("2026-07-28", "tools/call", "  echo ")));
        assertRejected(400, -32020, "1", validate(call, headers("2026-07-28", "tools/call", "other")));
        assertRejected(400, -32020, "1", validate(call, headers("2026-07-28", "tools/call", null)));

        String read = body(1, "resources/read", "2026-07-28", ",\"uri\":\"file:///a%20b.txt\"");
        accepted(
                validate(read, headers("2026-07-28", "resources/read", "file:///a%20b.txt")));
        assertRejected(400, -32020, "1", validate(read, headers("2026-07-28", "resources/read", "file:///a b.txt")));

        String get = body(1, "prompts/get", "2026-07-28", ",\"name\":\"p\"");
        assertRejected(400, -32020, "1", validate(get, headers("2026-07-28", "prompts/get", null)));
        String complete = body(1, "completion/complete", "2026-07-28", "");
        accepted(
                validate(complete, headers("2026-07-28", "completion/complete", null)));
    }

    @Test
    void headerNamesAreCaseInsensitive() {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("mcp-protocol-version", "2026-07-28");
        headers.put("MCP-METHOD", "tools/list");
        accepted(
                validate(body(1, "tools/list", "2026-07-28", ""), headers));
    }

    @Test
    void base64Sentinel() {
        String name = "Hello, 世界";
        String call = body(1, "tools/call", "2026-07-28", ",\"name\":\"" + name + "\"");
        accepted(
                validate(call, headers("2026-07-28", "tools/call", "=?base64?SGVsbG8sIOS4lueVjA==?=")));
        assertEquals("=?base64?literal?=", RequestValidator.decode(List.of("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=")));
        assertEquals("=?BASE64?SGk=?=", RequestValidator.decode(List.of("=?BASE64?SGk=?=")));
        assertRejected(400, -32020, "1", validate(call, headers("2026-07-28", "tools/call", "=?base64?!!!?=")));
        String invalidUtf8 = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(IllegalArgumentException.class,
                () -> RequestValidator.decode(List.of("=?base64?" + invalidUtf8 + "?=")));
    }

    @Test
    void invalidHeaderCharacters() {
        String call = body(1, "tools/call", "2026-07-28", ",\"name\":\"é\"");
        assertRejected(400, -32020, "1", validate(call, headers("2026-07-28", "tools/call", "é")));
        assertThrows(IllegalArgumentException.class, () -> RequestValidator.decode(List.of("a\u0001b")));
    }

    @Test
    void repeatedHeadersAreJoined() {
        assertEquals("a,b", RequestValidator.decode(List.of("a", "b")));
        String read = body(1, "resources/read", "2026-07-28", ",\"uri\":\"x://a,b\"");
        Headers headers = Headers.of(Map.of("MCP-Protocol-Version", List.of("2026-07-28"),
                "Mcp-Method", List.of("resources/read"), "Mcp-Name", List.of("x://a", "b")));
        assertTrue(RequestValidator.validate(read.getBytes(StandardCharsets.UTF_8), headers).isPresent());
    }

    static String body(Object id, String method, String version, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{"
                + META.formatted(version) + params + "}}";
    }

    static Map<String, String> headers(String version, String method, String name) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("MCP-Protocol-Version", version);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        return headers;
    }

    /**
     * What validation came to: a request, a notification (neither is set), or a rejection.
     */
    private record Result(Request request, Rejection rejection) {
    }

    static Headers lookup(Map<String, String> headers) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        headers.forEach((name, value) -> fields.put(name, List.of(value)));
        return Headers.of(fields);
    }

    private static Result validate(String body, Map<String, String> headers) {
        try {
            return new Result(RequestValidator.validate(body.getBytes(StandardCharsets.UTF_8), lookup(headers))
                    .orElse(null), null);
        } catch (Rejection e) {
            return new Result(null, e);
        }
    }

    private static Request accepted(Result result) {
        assertNull(result.rejection(), () -> String.valueOf(result.rejection().message()));
        assertNotNull(result.request());
        return result.request();
    }

    private static void notification(Result result) {
        assertNull(result.rejection(), () -> String.valueOf(result.rejection().message()));
        assertNull(result.request());
    }

    private static JsonObject assertRejected(int status, int code, String id, Result result) {
        Rejection rejection = result.rejection();
        assertNotNull(rejection, "accepted");
        JsonObject message = rejection.message();
        assertEquals(status, rejection.status(), message.toString());
        JsonObject error = message.getJsonObject("error");
        assertEquals(code, error.getInt("code"), message.toString());
        if (id == null) {
            assertFalse(message.containsKey("id"), message.toString());
        } else {
            assertEquals(Json.parse(id), message.get("id"));
        }
        return error;
    }
}
