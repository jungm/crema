package io.github.jungm.crema.it.protocol.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.tools.Tool;

/**
 * Features bound to the admin MCP Server at class level. ({@code @Singleton} isn't bean defining, so with
 * {@code bean-discovery-mode="annotated"} only OpenWebBeans would discover such a class.)
 */
@ApplicationScoped
@McpServer("admin")
public class AdminTools {

    @Tool(description = "Purges the caches")
    public String purge() {
        return "purged";
    }

    @Prompt(description = "Audit prompt")
    public String audit() {
        return "Audit the shop";
    }
}
