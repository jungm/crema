package io.github.jungm.crema.it.deployment.app;

import io.github.jungm.crema.McpApplication;
import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

@ApplicationScoped
public class UndeclaredServer {
    @McpServer("nowhere")
    @Tool(description = "Bound to an MCP Server no McpApplication declares")
    public String lost() {
        return "lost";
    }
}
