package io.github.jungm.crema.it.protocol;

import io.github.jungm.crema.it.mcp.Exchange;
import io.github.jungm.crema.it.mcp.McpClient;
import io.github.jungm.crema.it.mcp.WireSchema;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streamable HTTP and the envelope: header validation in the order the conformance suite requires (protocol-notes
 * G6), the {@code _meta} fields, versions, methods, notifications, {@code Origin} and cursors.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class TransportIT {

    @ArquillianResource
    private URL base;

    private McpClient mcp;

    @Deployment(testable = false)
    static WebArchive deployment() {
        return ProtocolWar.create("transport");
    }

    @BeforeEach
    void client() {
        mcp = McpClient.at(base, "mcp");
    }

    // 1. JSON

    @Test
    void unparseableBodyIsParseError() {
        Exchange exchange = mcp.post("tools/list").body("{\"jsonrpc\":").send();
        exchange.error(400, -32700);
        assertFalse(exchange.message().containsKey("id"));
    }

    // 2. JSON-RPC shape

    @Test
    void batchesAndResponsesAreInvalidRequests() {
        String request = mcp.post("tools/list").message().toString();
        mcp.post("tools/list").body("[" + request + "]").send().error(400, -32600);
        Exchange response = mcp.post("tools/list").body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}").send();
        response.error(400, -32600);
        assertEquals(1, response.message().getInt("id"));
        mcp.post("tools/list").body("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}").send()
                .error(400, -32600);
        mcp.post("tools/list").body("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}").send()
                .error(400, -32600);
    }

    // 3. headers present

    @Test
    void missingStandardHeadersAreHeaderMismatch() {
        mcp.post("tools/list").header("MCP-Protocol-Version", null).send().error(400, -32020);
        mcp.post("tools/list").header("Mcp-Method", null).send().error(400, -32020);
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", "").send().error(400, -32020);
    }

    @Test
    void missingHeaderWinsOverMissingMeta() {
        mcp.post("tools/list").header("Mcp-Method", null).withoutMeta().send().error(400, -32020);
    }

    @Test
    void legacyInitializeNamesTheSupportedVersion() {
        JsonObject error = mcp.post("initialize").withoutParams().header("MCP-Protocol-Version", null)
                .header("Mcp-Method", null).send().error(400, -32020);
        assertTrue(error.getString("message").contains("2026-07-28"), error::toString);
    }

    // 4. _meta

    @Test
    void missingMetaFieldsAreInvalidParamsWithStatus400() {
        for (McpClient.Post post : List.of(mcp.post("tools/list").withoutParams(),
                mcp.post("tools/list").withoutMeta(),
                mcp.post("tools/list").meta(McpClient.META_PROTOCOL_VERSION, (JsonValue) null),
                mcp.post("tools/list").meta(McpClient.META_CLIENT_CAPABILITIES, (JsonValue) null),
                mcp.post("server/discover").withoutMeta())) {
            Exchange exchange = post.id(Json.createValue("m-1")).send();
            exchange.error(400, -32602);
            assertEquals("m-1", exchange.message().getString("id"), "the error echoes the request id");
        }
    }

    // 5. headers match

    @Test
    void headersMustMatchTheBody() {
        mcp.post("tools/list").header("Mcp-Method", "prompts/list").send().error(400, -32020);
        mcp.post("tools/list").header("Mcp-Method", "TOOLS/LIST").send().error(400, -32020);
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", "add").send().error(400, -32020);
        mcp.post("resources/read").params(Json.createObjectBuilder().add("uri", "app://readme").build())
                .header("Mcp-Name", "app://README").send().error(400, -32020);
        mcp.post("tools/list").meta(McpClient.META_PROTOCOL_VERSION, "v999.0.0").send().error(400, -32020);
    }

    @Test
    void headerNamesAreCaseInsensitiveAndValuesAreTrimmed() {
        mcp.post("tools/list").header("Mcp-Method", null).header("mcp-method", "tools/list")
                .header("MCP-Protocol-Version", null).header("mcp-protocol-version", "2026-07-28").send().result();
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", "  health ").send().result();
    }

    @Test
    void base64SentinelIsDecoded() {
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", McpClient.sentinel("health")).send().result();
        mcp.post("resources/read").params(Json.createObjectBuilder().add("uri", "app://users/Jürgen/files/ä ö")
                .build()).header("Mcp-Name", McpClient.sentinel("app://users/Jürgen/files/ä ö")).send()
                .result();
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", McpClient.sentinel("add")).send().error(400, -32020);
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", "=?base64?not base64!?=").send().error(400, -32020);
        mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "health").build())
                .header("Mcp-Name", "=?base64?/w==?=").send().error(400, -32020);
    }

    // 6. version

    @Test
    void unsupportedVersionListsTheSupportedOnes() {
        JsonObject error = mcp.post("tools/list").meta(McpClient.META_PROTOCOL_VERSION, "v999.0.0")
                .header("MCP-Protocol-Version", "v999.0.0").send().error(400, -32022);
        assertEquals(Json.createArrayBuilder().add("2026-07-28").build(),
                error.getJsonObject("data").getJsonArray("supported"));
        assertEquals("v999.0.0", error.getJsonObject("data").getString("requested"));
        mcp.post("server/discover").meta(McpClient.META_PROTOCOL_VERSION, "2025-11-25")
                .header("MCP-Protocol-Version", "2025-11-25").send().error(400, -32022);
    }

    @Test
    void unsupportedVersionWinsOverUnknownMethod() {
        mcp.post("nope/nope").meta(McpClient.META_PROTOCOL_VERSION, "v999.0.0")
                .header("MCP-Protocol-Version", "v999.0.0").send().error(400, -32022);
    }

    // 7. method

    @Test
    void unknownAndRemovedMethodsAre404() {
        for (String method : List.of("unknown/method", "initialize", "ping", "logging/setLevel",
                "resources/subscribe", "resources/unsubscribe", "subscriptions/listen")) {
            Exchange exchange = mcp.request(method);
            exchange.error(404, -32601);
            assertTrue(exchange.contentType().startsWith("application/json"), method);
            assertFalse(exchange.body().strip().contains("\n"), method);
        }
    }

    // transport

    @Test
    void getAndDeleteAre405() {
        for (String method : List.of("GET", "DELETE")) {
            HttpResponse<String> response = mcp.send(method, Map.of("Accept", "text/event-stream"));
            assertEquals(405, response.statusCode(), method);
            assertEquals("POST", response.headers().firstValue("Allow").orElse(null), method);
        }
    }

    @Test
    void notificationsAreAcceptedWithoutBody() {
        Exchange exchange = mcp.post("notifications/cancelled").notification()
                .params(Json.createObjectBuilder().add("requestId", 1).build()).send();
        assertEquals(202, exchange.status());
        assertEquals("", exchange.body());
    }

    @Test
    void sessionIdIsIgnored() {
        Exchange exchange = mcp.post("tools/list").header("Mcp-Session-Id", "abc").header("Last-Event-ID", "5")
                .send();
        exchange.result();
        assertTrue(exchange.header("Mcp-Session-Id").isEmpty());
    }

    @Test
    void acceptHeaderIsNotRequired() {
        mcp.post("tools/list").header("Accept", null).send().result();
    }

    @Test
    void requestIdsKeepTheirType() {
        assertEquals("abc", mcp.post("tools/list").id(Json.createValue("abc")).send().message().getString("id"));
        assertEquals(42, mcp.post("tools/list").id(Json.createValue(42)).send().message().getInt("id"));
    }

    @Test
    void anyCursorIsInvalid() {
        for (String method : List.of("tools/list", "resources/list", "resources/templates/list", "prompts/list")) {
            JsonObject error = mcp.post(method).params(Json.createObjectBuilder().add("cursor", "c1").build())
                    .send().error(200, -32602);
            assertEquals("Invalid cursor", error.getString("message"), method);
        }
    }

    @Test
    void mrtrParametersAreIgnored() {
        JsonObject result = mcp.request("tools/call", Json.createObjectBuilder().add("name", "health")
                .add("inputResponses", JsonValue.EMPTY_JSON_OBJECT).add("requestState", "opaque").build())
                .result();
        assertEquals("complete", result.getString("resultType"));
    }

    @Test
    void concurrentRequestsAllSucceed() {
        List<CompletableFuture<Exchange>> requests = List.of(1, 2, 3, 4, 5).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> mcp.request("tools/list"))).toList();
        requests.forEach(r -> r.join().result());
    }

    @Test
    void oversizedBodiesAre413() {
        String padding = "x".repeat(5 * 1024 * 1024);
        Exchange exchange = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "echo")
                .add("arguments", Json.createObjectBuilder().add("text", padding)).build()).send();
        exchange.error(413, -32600);
        assertFalse(exchange.message().containsKey("id"));
    }

    // Origin

    @Test
    void foreignOriginIsForbidden() {
        Exchange exchange = mcp.post("tools/list").header("Origin", "https://evil.example.com").send();
        assertEquals(403, exchange.status(), exchange::describe);
        assertFalse(exchange.message().containsKey("id"));
        assertEquals(403, mcp.send("GET", Map.of("Origin", "https://evil.example.com")).statusCode());
    }

    @Test
    void loopbackOriginsAreAllowed() {
        for (String origin : List.of("http://localhost:3000", "https://127.0.0.1", "http://[::1]:8080")) {
            mcp.post("tools/list").header("Origin", origin).send().result();
        }
    }

    @Test
    void matrixParametersDontBypassTheChecks() {
        McpClient matrix = McpClient.at(base, "mcp/;x=1");
        Exchange origin = matrix.post("tools/list").header("Origin", "https://evil.example.com").send();
        assertTrue(origin.status() == 403 || origin.status() == 404, origin::describe);
        Exchange missingHeader = matrix.post("tools/list").header("Mcp-Method", null).send();
        assertTrue(missingHeader.status() == 400 || missingHeader.status() == 404, missingHeader::describe);
    }

    @Test
    void dnsRebindingIsForbidden() {
        Exchange exchange = mcp.post("tools/list").header("Host", "evil.example.com")
                .header("Origin", "http://evil.example.com").send();
        assertEquals(403, exchange.status(), exchange::describe);
        String host = mcp.endpoint().getHost() + ":" + mcp.endpoint().getPort();
        mcp.post("tools/list").header("Host", host).header("Origin", "http://" + host).send().result();
    }

    @Test
    void theSchemaCheckItselfWorks() {
        assertFalse(WireSchema.get().violations("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                "tools/list").isEmpty(), "a result without resultType, ttlMs and cacheScope must be rejected");
        assertTrue(WireSchema.get().violations("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32022,"
                + "\"message\":\"x\",\"data\":{\"supported\":[\"2026-07-28\"],\"requested\":\"1\"}}}", "tools/list")
                .isEmpty());
        assertFalse(WireSchema.get().violations("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32022,"
                + "\"message\":\"x\"}}", "tools/list").isEmpty(), "-32022 requires data");
    }
}
