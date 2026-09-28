package io.github.jungm.crema.it.protocol.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

/** A second MCP Server, nested below the default one's path. */
@ApplicationPath("mcp/admin")
@McpServerInfo(name = "admin", title = "Back office")
public class AdminMcp extends McpApplication {
}
