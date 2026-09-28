package io.github.jungm.crema.it.coexistence.app;

import io.github.jungm.crema.McpApplication;
import jakarta.ws.rs.ApplicationPath;

/** The MCP Server next to the application's API. */
@ApplicationPath("mcp")
public class CoexistMcp extends McpApplication {
}
