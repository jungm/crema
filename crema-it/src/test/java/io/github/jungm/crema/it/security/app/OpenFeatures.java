package io.github.jungm.crema.it.security.app;

import java.security.Principal;

import org.mcpjava.server.McpServer;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tools of the open MCP Server.
 */
@ApplicationScoped
@McpServer("open")
public class OpenFeatures {

    @Tool(description = "Anyone")
    public String free(McpCaller caller, Principal principal) {
        return caller == null && principal == null ? "anonymous" : "caller " + principal.getName();
    }

    @Tool(description = "Role admin of the Runtime")
    @RolesAllowed("admin")
    public String admin() {
        return "admin";
    }
}
