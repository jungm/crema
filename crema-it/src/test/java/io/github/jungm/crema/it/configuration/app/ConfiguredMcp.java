package io.github.jungm.crema.it.configuration.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

/**
 * The default MCP Server, whose annotated title, version and instructions MicroProfile Config overrides; its
 * description stays as annotated.
 */
@ApplicationPath("mcp")
@McpServerInfo(title = "Annotated title", version = "1.0.0", instructions = "Annotated instructions",
        description = "Annotated description")
public class ConfiguredMcp extends McpApplication {
}
