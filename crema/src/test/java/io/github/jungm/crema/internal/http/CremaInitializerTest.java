package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        assertEquals(Set.of(McpEndpoint.class, McpEndpointFilter.class, ResourceMetadataEndpoint.class),
                application.getClasses());
    }
}
