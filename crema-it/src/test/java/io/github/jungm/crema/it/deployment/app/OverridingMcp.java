package io.github.jungm.crema.it.deployment.app;

import java.util.Set;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("mcp-custom")
@McpServerInfo(name = "custom")
public class OverridingMcp extends McpApplication {
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of();
    }
}
