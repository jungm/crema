package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriInfo;

/**
 * Which path below the application path a request addresses.
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
        assertEquals(List.of(), McpEndpointFilter.path(uriInfo()));
        assertEquals(List.of(), McpEndpointFilter.path(uriInfo("")));
        assertEquals(List.of(), McpEndpointFilter.path(uriInfo("", "")), "matrix parameters on an empty segment");
    }

    @Test
    void subPathsAreNot() {
        assertEquals(List.of("tools"), McpEndpointFilter.path(uriInfo("tools")));
        assertEquals(List.of("x"), McpEndpointFilter.path(uriInfo("", "x")));
        assertEquals(List.of(".well-known", "oauth-protected-resource"),
                McpEndpointFilter.path(uriInfo(".well-known", "oauth-protected-resource", "")));
    }
}
