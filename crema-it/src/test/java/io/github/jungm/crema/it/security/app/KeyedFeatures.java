package io.github.jungm.crema.it.security.app;

import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tools of the MCP Server authenticated by API key.
 */
@ApplicationScoped
@McpServer("keyed")
public class KeyedFeatures {

    @Tool(description = "The caller and its claims")
    public String whoami(McpCaller caller) {
        return caller.getName() + " " + caller.claims();
    }

    @Tool(description = "Role admin")
    @RolesAllowed("admin")
    public String admin() {
        return "admin";
    }
}
