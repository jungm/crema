package io.github.jungm.crema.it.security.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

/**
 * An open MCP Server in the same WAR: the caller is the Runtime's.
 */
@ApplicationPath("open")
@McpServerInfo(name = "open")
public class OpenMcp extends McpApplication {
}
