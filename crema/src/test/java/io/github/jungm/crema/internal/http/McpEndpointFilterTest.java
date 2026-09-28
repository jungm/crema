package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.internal.protocol.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriInfo;

/**
 * Which requests the filter screens as requests to the MCP Endpoint.
 */
class McpEndpointFilterTest {

    private static UriInfo uriInfo(String... segments) {
        List<PathSegment> pathSegments = Arrays.stream(segments).map(McpEndpointFilterTest::segment).toList();
        return (UriInfo) Proxy.newProxyInstance(McpEndpointFilterTest.class.getClassLoader(),
                new Class<?>[] {UriInfo.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPathSegments")) {
                        return pathSegments;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PathSegment segment(String path) {
        return (PathSegment) Proxy.newProxyInstance(McpEndpointFilterTest.class.getClassLoader(),
                new Class<?>[] {PathSegment.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPath")) {
                        return path;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theApplicationPathItselfIsTheEndpoint() {
        assertTrue(McpEndpointFilter.isEndpoint(uriInfo()));
        assertTrue(McpEndpointFilter.isEndpoint(uriInfo("")));
        assertTrue(McpEndpointFilter.isEndpoint(uriInfo("", "")), "matrix parameters on an empty segment");
    }

    @Test
    void subPathsAreNot() {
        assertFalse(McpEndpointFilter.isEndpoint(uriInfo("tools")));
        assertFalse(McpEndpointFilter.isEndpoint(uriInfo("", "x")));
        assertFalse(McpEndpointFilter.isEndpoint(uriInfo(".well-known", "oauth-protected-resource")));
    }

    @Test
    void unscreenedRequestsFailClosed() {
        assertEquals(500, McpEndpoint.UNSCREENED.status());
        JsonObject body = (JsonObject) Json.parse(McpEndpoint.UNSCREENED.body());
        assertFalse(body.containsKey("id"));
        assertEquals(-32603, body.getJsonObject("error").getInt("code"));
    }
}
