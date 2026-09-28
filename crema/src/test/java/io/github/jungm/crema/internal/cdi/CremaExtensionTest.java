package io.github.jungm.crema.internal.cdi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcpjava.server.ContentEncoder;
import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;
import org.mcpjava.server.Icons;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.http.Headers;
import io.github.jungm.crema.internal.http.HttpReply;
import io.github.jungm.crema.internal.http.HttpRequest;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * The CDI extension in Weld SE, with the {@code McpApplication} subclasses declared the way the
 * {@code ServletContainerInitializer} declares them.
 */
class CremaExtensionTest {

    static class App {
    }

    @ApplicationScoped
    public static class Counter {
        private int value;

        int next() {
            return ++value;
        }
    }

    public record Point(int x, int y) {
    }

    @Dependent
    public static class Tools {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @Inject
        Counter counter;

        @Tool(description = "Counts")
        public int count() {
            return counter.next();
        }

        @Tool(description = "Point")
        public Point point() {
            return new Point(1, 2);
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class PointEncoder implements ContentEncoder<Point> {
        @Override
        public ContentBlock encode(Point point) {
            return TextContent.of(point.x() + "/" + point.y());
        }

        @Override
        public Class<Point> getType() {
            return Point.class;
        }
    }

    @ApplicationScoped
    public static class BeanIcons implements IconProvider {
        @Inject
        Counter counter;

        @Override
        public List<Icon> getIcons(FeatureType featureType, String name) {
            return List.of(Icon.of("https://example.com/" + name + ".png", "image/png"));
        }
    }

    @Dependent
    public static class Prompts {
        @Prompt(description = "With icons")
        @Icons(iconProvider = BeanIcons.class)
        public String iconic() {
            return "hi";
        }
    }

    @Dependent
    public static class Invalid {
        @Prompt
        public int wrong() {
            return 0;
        }
    }

    @Dependent
    public static class Unbound {
        @Tool
        @McpServer("elsewhere")
        public void tool() {
        }
    }

    private static final List<Object> LISTENERS = new ArrayList<>();
    private static final ServletContext CONTEXT = context(CremaExtensionTest.class.getClassLoader());

    @AfterEach
    void reset() {
        LISTENERS.clear();
        CremaDeployment.reset();
    }

    @Test
    void servesFeaturesThroughCdi() {
        assertEquals(List.of(), CremaDeployment.applicationsDiscovered(CONTEXT, applications()));
        Tools.DESTROYED.set(0);
        try (WeldContainer container = weld(Tools.class, Counter.class, PointEncoder.class, BeanIcons.class,
                Prompts.class).initialize()) {
            McpTransport transport = CremaDeployment.transport().orElseThrow();
            McpServerModel server = transport.server(App.class).orElseThrow();
            assertEquals("1", text(call(transport, server, "tools/call", "count")));
            assertEquals("2", text(call(transport, server, "tools/call", "count")));
            assertTrue(Tools.DESTROYED.get() >= 2, "dependent instances are destroyed after each call");
            assertEquals("1/2", text(call(transport, server, "tools/call", "point")));
            JsonObject prompts = result(call(transport, server, "prompts/list", null));
            assertEquals(Json.parse("[{\"src\":\"https://example.com/iconic.png\",\"mimeType\":\"image/png\"}]"),
                    prompts.getJsonArray("prompts").getJsonObject(0).get("icons"));
        }
    }

    @Test
    void applicationsMayArriveAfterFeatures() {
        try (WeldContainer container = weld(Tools.class, Counter.class).initialize()) {
            assertEquals(Optional.empty(), CremaDeployment.transport());
            assertEquals(List.of(), CremaDeployment.applicationsDiscovered(CONTEXT, applications()));
            McpTransport transport = CremaDeployment.transport().orElseThrow();
            assertEquals("1", text(call(transport, transport.server(App.class).orElseThrow(), "tools/call",
                    "count")));
        }
    }

    @Test
    void invalidFeaturesFailDeployment() {
        CremaDeployment.applicationsDiscovered(CONTEXT, applications());
        DeploymentException e = assertThrows(DeploymentException.class, () -> weld(Invalid.class).initialize());
        assertTrue(e.getMessage().contains(Invalid.class.getName() + "#wrong(): Prompt methods must return"),
                e.getMessage());
    }

    @Test
    void unknownServersFailDeploymentInCdi() {
        CremaDeployment.applicationsDiscovered(CONTEXT, applications());
        DeploymentException e = assertThrows(DeploymentException.class, () -> weld(Unbound.class).initialize());
        assertTrue(e.getMessage().contains("is bound to the MCP Server 'elsewhere'"), e.getMessage());
    }

    @Test
    void unknownServersFailDeploymentInTheInitializer() {
        try (WeldContainer container = weld(Unbound.class).initialize()) {
            List<String> problems = CremaDeployment.applicationsDiscovered(CONTEXT, applications());
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("is bound to the MCP Server 'elsewhere'"), problems.get(0));
            assertEquals(Optional.empty(), CremaDeployment.transport());
        }
    }

    @Test
    void secondWebApplicationWithApplicationsFails() {
        assertEquals(List.of(), CremaDeployment.applicationsDiscovered(CONTEXT, applications()));
        ServletContext other = context(new ClassLoader() { });
        assertEquals(List.of(CremaDeployment.SHARED), CremaDeployment.applicationsDiscovered(other, applications()));
        assertEquals(List.of(), CremaDeployment.applicationsDiscovered(other,
                new CremaDeployment.Applications(List.of(), CremaSettings.defaults())));
        assertEquals(List.of(), CremaDeployment.applicationsDiscovered(CONTEXT, applications()));
    }

    @Test
    void secondWebApplicationWithFeaturesFails() {
        try (WeldContainer container = weld(Tools.class, Counter.class).initialize()) {
            assertTrue(CremaDeployment.transport().isEmpty());
        }
        DeploymentException e = assertThrows(DeploymentException.class, () -> weld(Prompts.class).initialize());
        assertTrue(e.getMessage().contains(CremaDeployment.SHARED), e.getMessage());
        weld().initialize().close();
    }

    @Test
    void applicationsWithoutCdiFailToStart() {
        assertEquals(List.of(), CremaDeployment.applicationsDiscovered(CONTEXT, applications()));
        ServletContextListener check = (ServletContextListener) LISTENERS.get(0);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> check.contextInitialized(new ServletContextEvent(CONTEXT)));
        assertEquals(CremaDeployment.CDI_INACTIVE, e.getMessage());
        try (WeldContainer container = weld(Tools.class, Counter.class).initialize()) {
            check.contextInitialized(new ServletContextEvent(CONTEXT));
        }
    }

    @Test
    void noApplicationsNoCdiCheck() {
        CremaDeployment.applicationsDiscovered(CONTEXT,
                new CremaDeployment.Applications(List.of(), CremaSettings.defaults()));
        assertEquals(List.of(), LISTENERS);
    }

    private static ServletContext context(ClassLoader loader) {
        return (ServletContext) Proxy.newProxyInstance(CremaExtensionTest.class.getClassLoader(),
                new Class<?>[] {ServletContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addListener":
                            LISTENERS.add(args[0]);
                            return null;
                        case "getClassLoader":
                            return loader;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "ServletContext";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static Weld weld(Class<?>... beans) {
        return new Weld().disableDiscovery().addExtension(new CremaExtension()).addBeanClasses(beans);
    }

    private static CremaDeployment.Applications applications() {
        return new CremaDeployment.Applications(List.of(new CremaDeployment.Application(App.class,
                ServerSettings.resolve(null, ConfigLookup.none(), Optional::empty), null)), CremaSettings.defaults());
    }

    private static HttpReply call(McpTransport transport, McpServerModel server, String method, String name) {
        String params = name == null ? "" : ",\"name\":\"" + name + "\"";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}" + params + "}}";
        Map<String, List<String>> headers = name == null
                ? Map.of("MCP-Protocol-Version", List.of("2026-07-28"), "Mcp-Method", List.of(method))
                : Map.of("MCP-Protocol-Version", List.of("2026-07-28"), "Mcp-Method", List.of(method), "Mcp-Name",
                        List.of(name));
        try {
            return (HttpReply) transport.handle(server, HttpRequest.post(body.getBytes(StandardCharsets.UTF_8),
                    Headers.of(headers)), Caller.ANONYMOUS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject result(HttpReply reply) {
        assertEquals(200, reply.status(), reply.body());
        return ((JsonObject) Json.parse(reply.body())).getJsonObject("result");
    }

    private static String text(HttpReply reply) {
        return result(reply).getJsonArray("content").getJsonObject(0).getString("text");
    }
}
