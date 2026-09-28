package io.github.jungm.crema.it.coexistence;

import io.github.jungm.crema.it.coexistence.app.ApiApplication;
import io.github.jungm.crema.it.coexistence.app.AppProviders;
import io.github.jungm.crema.it.mcp.CremaLibs;
import io.github.jungm.crema.it.mcp.Exchange;
import io.github.jungm.crema.it.mcp.McpClient;
import io.github.jungm.crema.it.support.Findings;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code McpApplication} at {@code /mcp} next to the application's own scanning JAX-RS application at
 * {@code /api}, which has its own root resource at {@code @Path("")} and its own providers: a JSON-B
 * {@code ContextResolver} with snake_case names, an {@code ExceptionMapper<Throwable>} and a response filter. The
 * scanning application may pick up Crema's resource class; the application's root resource must still win, and
 * the application's providers must not change MCP traffic.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class CoexistenceIT {

    private static final Findings FINDINGS = Findings.of(CoexistenceIT.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @ArquillianResource
    private URL base;

    private McpClient mcp;

    @Deployment(testable = false)
    static WebArchive deployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "coexistence.war")
                .addPackage(ApiApplication.class.getPackage())
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return CremaLibs.addTo(war);
    }

    @BeforeEach
    void client() {
        mcp = McpClient.at(base, "mcp");
    }

    @Test
    void applicationRootResourceStillServesGet() {
        HttpResponse<String> response = api("GET", "api/", null, Map.of("Accept", "application/json"));
        assertEquals(200, response.statusCode(), response::body);
        JsonObject status = parse(response.body());
        assertEquals("shop", status.getString("service_name"), "the application's JSON-B resolver applies");
        assertEquals("yes", response.headers().firstValue(AppProviders.HEADER).orElse(null));
        assertEquals(200, api("GET", "api", null, Map.of("Accept", "application/json")).statusCode());
    }

    @Test
    void applicationRootResourceStillServesPost() {
        HttpResponse<String> response = api("POST", "api/", "{\"service_name\":\"x\",\"open_orders\":1}",
                Map.of("Content-Type", "application/json", "Accept", "application/json"));
        assertEquals(200, response.statusCode(), response::body);
        assertEquals("x-echo", parse(response.body()).getString("service_name"));
    }

    @Test
    void mcpRequestToTheApiIsNotServedAsMcp() {
        String body = mcp.post("tools/list").message().toString();
        HttpResponse<String> response = api("POST", "api/", body, Map.of("Content-Type", "application/json",
                "Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "tools/list"));
        FINDINGS.record("MCP request POSTed to /api/", response.statusCode() + " " + response.body());
        assertFalse(response.body().contains("\"tools\""), response::body);
    }

    @Test
    void applicationExceptionMapperStillAppliesToTheApi() {
        HttpResponse<String> response = api("GET", "api/boom", null, Map.of());
        assertEquals(418, response.statusCode());
    }

    @Test
    void mcpWireFormatIsUnaffected() {
        Exchange list = mcp.request("tools/list");
        list.result();
        assertTrue(list.contentType().startsWith("application/json"), list::describe);

        JsonObject result = mcp.callTool("status", null).result();
        JsonObject structured = result.getJsonObject("structuredContent");
        assertEquals("shop", structured.getString("serviceName"), "Crema's own JSON-B, not the application's");
        assertEquals(3, structured.getInt("openOrders"));

        JsonObject failed = mcp.callTool("fail", null).result();
        assertTrue(failed.getBoolean("isError"));
        assertTrue(mcp.callTool("fatal", null).result().getBoolean("isError"));

        mcp.request("nope/nope").error(404, -32601);
        mcp.post("tools/list").header("Mcp-Method", null).send().error(400, -32020);
        Exchange origin = mcp.post("tools/list").header("Origin", "https://evil.example.com").send();
        assertEquals(403, origin.status());

        Exchange stream = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "progress").build())
                .meta("progressToken", "p").send();
        assertTrue(stream.isEventStream(), stream::describe);
        assertEquals(2, stream.messages().size());
        assertEquals("done", stream.result().getJsonArray("content").getJsonObject(0).getString("text"));
        assertEquals(405, mcp.send("GET", Map.of()).statusCode());

        for (Exchange exchange : new Exchange[] {list, origin, stream}) {
            FINDINGS.record("application response filter on MCP " + exchange.status() + " " + exchange.contentType(),
                    exchange.header(AppProviders.HEADER).map(v -> "applied").orElse("not applied"));
        }
    }

    @Test
    void applicationResponseFilterDoesNotApplyToMcp() {
        assertTrue(mcp.request("tools/list").header(AppProviders.HEADER).isEmpty(), "JSON response");
        assertTrue(mcp.post("tools/list").header("Mcp-Method", null).send().header(AppProviders.HEADER).isEmpty(),
                "error response");
        assertTrue(mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "progress").build())
                .meta("progressToken", "p").send().header(AppProviders.HEADER).isEmpty(), "SSE response");
    }

    private HttpResponse<String> api(String method, String path, String body, Map<String, String> headers) {
        String root = base.toExternalForm();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + (root.endsWith("/") ? "" : "/")
                + path)).timeout(Duration.ofSeconds(30)).method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
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

    private static JsonObject parse(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
