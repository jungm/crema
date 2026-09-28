package io.github.jungm.crema.it.conformance.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;

/** The MCP Server the conformance suite tests. */
@ApplicationPath("mcp")
@McpServerInfo(title = "Crema conformance fixtures", version = "1.0.0")
public class ConformanceMcp extends McpApplication {
}
