package io.github.jungm.crema.it.security.app;

import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tools of the MCP Server with Basic authentication.
 */
@ApplicationScoped
@McpServer("basic")
public class BasicFeatures {

    @Tool(description = "The caller")
    public String whoami(McpCaller caller) {
        return caller.getName();
    }

    @Tool(description = "Role admin")
    @RolesAllowed("admin")
    public String admin() {
        return "admin";
    }
}
