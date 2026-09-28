package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.jungm.crema.McpApplication;

class CremaInitializerTest {

    static class Plain extends McpApplication {
    }

    static class Sneaky extends McpApplication {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of();
        }
    }

    static class SneakySubclass extends Sneaky {
        @Override
        public Map<String, Object> getProperties() {
            return Map.of();
        }
    }

    abstract static class Base extends McpApplication {
    }

    static class Chatty extends McpApplication {
        static String greeting() {
            return "hi";
        }

        void helper() {
        }
    }

    @Test
    void subclassesMustNotDeclareMethods() {
        List<String> problems = new ArrayList<>();
        CremaInitializer.checkOverrides(Chatty.class, problems);
        assertEquals(List.of("McpApplication " + Chatty.class.getName() + ": " + Chatty.class.getName()
                + " declares the methods [greeting(), helper()], but McpApplication subclasses must not declare "
                + "methods"), problems);
    }

    @Test
    void subclassesMustNotChooseTheirJaxRsClasses() {
        List<String> problems = new ArrayList<>();
        CremaInitializer.checkOverrides(Plain.class, problems);
        assertEquals(List.of(), problems);
        CremaInitializer.checkOverrides(SneakySubclass.class, problems);
        assertEquals(List.of(
                "McpApplication " + SneakySubclass.class.getName() + ": " + SneakySubclass.class.getName()
                        + " overrides getProperties(), but only Crema decides which JAX-RS classes serve MCP traffic",
                "McpApplication " + SneakySubclass.class.getName() + ": " + Sneaky.class.getName()
                        + " overrides getClasses(), but only Crema decides which JAX-RS classes serve MCP traffic"),
                problems);
    }

    @Test
    void onlyConcreteSubclassesDeclareServers() {
        assertTrue(CremaInitializer.isMcpApplication(Plain.class));
        assertFalse(CremaInitializer.isMcpApplication(Base.class));
        assertFalse(CremaInitializer.isMcpApplication(McpApplication.class));
    }

    @Test
    void applicationNamesItsSubclass() {
        Plain application = new Plain();
        assertEquals(Map.of(McpApplication.APPLICATION_PROPERTY, Plain.class), application.getProperties());
        assertEquals(Set.of(McpEndpoint.class, McpEndpointFilter.class), application.getClasses());
    }

    @jakarta.ws.rs.ApplicationPath("mcp/admin/")
    static class Nested extends McpApplication {
    }

    @jakarta.ws.rs.ApplicationPath("/")
    static class Root extends McpApplication {
    }

    @jakarta.ws.rs.ApplicationPath("/api/*")
    static class Wildcard extends McpApplication {
    }

    @Test
    void responsesAreMadeFinalOnTheApplicationPath() {
        assertEquals(Optional.of("/mcp/admin/*"), CremaInitializer.urlPattern(Nested.class));
        assertEquals(Optional.of("/*"), CremaInitializer.urlPattern(Root.class));
        assertEquals(Optional.of("/api/*"), CremaInitializer.urlPattern(Wildcard.class));
        assertEquals(Optional.empty(), CremaInitializer.urlPattern(Plain.class));
    }

    @Test
    void deploymentFailureStopsTheWebApplication() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CremaInitializer.DeploymentFailure("Invalid MCP Servers").contextInitialized(null));
        assertEquals("Invalid MCP Servers", e.getMessage());
    }
}
