package io.github.jungm.crema.it.security.app;

import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tools of the {@code @DenyAll} MCP Server.
 */
@ApplicationScoped
@McpServer("denied")
public class DeniedFeatures {

    @Tool(description = "Denied by the McpApplication")
    public String denied() {
        return "denied";
    }

    @Tool(description = "Permitted by the method")
    @PermitAll
    public String permitted() {
        return "permitted";
    }
}
