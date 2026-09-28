package io.github.jungm.crema.it.coexistence;

import io.github.jungm.crema.it.coexistence.app.ApiApplication;
import io.github.jungm.crema.it.coexistence.app.AppFilters;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code McpApplication} at {@code /mcp} next to the application's own scanning JAX-RS application at
 * {@code /api}, which has its own root resource at {@code @Path("")} and its own providers: a JSON-B
 * {@code ContextResolver} with snake_case names, an {@code ExceptionMapper<Throwable>}, a response filter, a
 * post-matching request filter that requires a key and a {@code @PreMatching} request filter that blocks on a
 * header. The scanning application may pick up Crema's resource class; the application's root resource must still
 * win, and the application's providers must not change MCP traffic. The MCP Server also has a {@code @RequestScoped}
 * Feature bean with an interceptor.
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
        assertApplicationJsonb(parse(response.body()), "shop", response.body());
        assertEquals("yes", response.headers().firstValue(AppProviders.HEADER).orElse(null));
        assertEquals(200, api("GET", "api", null, Map.of("Accept", "application/json")).statusCode());
    }

    @Test
    void applicationRootResourceStillServesPost() {
        // both naming styles, so that the body binds whether or not TomEE applies the application's resolver
        HttpResponse<String> response = api("POST", "api/",
                "{\"service_name\":\"x\",\"open_orders\":1,\"serviceName\":\"x\",\"openOrders\":1}",
                Map.of("Content-Type", "application/json", "Accept", "application/json"));
        assertEquals(200, response.statusCode(), response::body);
        assertApplicationJsonb(parse(response.body()), "x-echo", response.body());
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

    @Test
    void applicationRequestFiltersApplyToTheApi() {
        HttpResponse<String> withoutKey = raw("GET", "api/", Map.of("Accept", "application/json"));
        assertEquals(401, withoutKey.statusCode(), withoutKey::body);
        HttpResponse<String> blocked = api("GET", "api/", null, Map.of("Accept", "application/json",
                AppFilters.BLOCK_HEADER, "yes"));
        assertEquals(AppFilters.BLOCKED, blocked.statusCode(), blocked::body);
    }

    /**
     * The application's post-matching filter would reject every MCP request (none carries the application's key),
     * so MCP working at all shows that it doesn't run. Its pre-matching filter could run before Crema's only where
     * the Runtime applies the application's providers to the {@code McpApplication} (TomEE) and orders it first;
     * Crema's filter has the highest priority, so it answers the request before the application's filter runs.
     */
    @Test
    void applicationRequestFiltersDontApplyToMcp() {
        Exchange list = mcp.request("tools/list");
        list.result();
        FINDINGS.record("application post-matching filter (401 without key) on MCP", list.status() + "");

        Exchange blocked = mcp.post("tools/list").header(AppFilters.BLOCK_HEADER, "yes").send();
        FINDINGS.record("application @PreMatching filter (451 with X-App-Block) on MCP", blocked.status() + " "
                + blocked.contentType());
        assertEquals(200, blocked.status(), blocked::describe);

        Exchange stream = mcp.post("tools/call").params(Json.createObjectBuilder().add("name", "progress").build())
                .meta("progressToken", "p").header(AppFilters.BLOCK_HEADER, "yes").send();
        assertTrue(stream.isEventStream(), stream::describe);
        assertEquals(405, mcp.send("GET", Map.of(AppFilters.BLOCK_HEADER, "yes")).statusCode());
    }

    @Test
    void requestScopedAndInterceptedFeatureBean() {
        for (int i = 0; i < 2; i++) {
            JsonObject result = mcp.callTool("counted", null).result();
            assertEquals("count 2", result.getJsonArray("content").getJsonObject(0).getString("text"),
                    "the interceptor and the Tool count in the same, fresh request scope");
        }
    }

    @Test
    void optionsIsNotAllowedAndAnswersNoCors() {
        HttpResponse<String> preflight = mcp.send("OPTIONS", Map.of("Origin", "https://app.example.com",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type"));
        FINDINGS.record("OPTIONS preflight on the MCP Endpoint", preflight.statusCode() + " "
                + preflight.headers().map());
        assertEquals(403, preflight.statusCode(), "the foreign Origin is rejected first");
        HttpResponse<String> options = mcp.send("OPTIONS", Map.of());
        FINDINGS.record("OPTIONS on the MCP Endpoint", options.statusCode() + " " + options.headers().map());
        assertEquals(405, options.statusCode(), options::body);
        assertEquals(List.of("POST"), options.headers().allValues("Allow"));
        HttpResponse<String> loopback = mcp.send("OPTIONS", Map.of("Origin", "http://localhost:3000",
                "Access-Control-Request-Method", "POST"));
        assertEquals(405, loopback.statusCode(), loopback::body);
        for (HttpResponse<String> response : List.of(preflight, options, loopback)) {
            assertTrue(response.headers().map().keySet().stream()
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-")),
                    () -> response.headers().map().toString());
        }
    }

    @Test
    void otherPathsBelowTheMcpEndpointAreNotFound() {
        HttpResponse<String> response = raw("GET", "mcp/tools", Map.of());
        assertEquals(404, response.statusCode(), response::body);
        assertFalse(response.headers().firstValue(AppProviders.HEADER).isPresent());
    }

    /**
     * The application's JSON-B resolver (snake_case) applies to its API. TomEE ignores it once another WAR on the
     * same server has used JSON-B through JAX-RS (so the order of the test classes decides); there it is recorded.
     */
    private static void assertApplicationJsonb(JsonObject status, String serviceName, String body) {
        if (Findings.RUNTIME.equals("tomee") && !status.containsKey("service_name")) {
            FINDINGS.record("application ContextResolver<Jsonb> on /api", "ignored: " + body);
            assertEquals(Json.createValue(serviceName), status.get("serviceName"), body);
            return;
        }
        assertEquals(Json.createValue(serviceName), status.get("service_name"), body);
    }

    /**
     * A request without the application's key.
     */
    private HttpResponse<String> raw(String method, String path, Map<String, String> headers) {
        String root = base.toExternalForm();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + (root.endsWith("/") ? "" : "/")
                + path)).timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
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

    private HttpResponse<String> api(String method, String path, String body, Map<String, String> headers) {
        String root = base.toExternalForm();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + (root.endsWith("/") ? "" : "/")
                + path)).timeout(Duration.ofSeconds(30)).method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        request.header(AppFilters.KEY_HEADER, AppFilters.KEY);
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
