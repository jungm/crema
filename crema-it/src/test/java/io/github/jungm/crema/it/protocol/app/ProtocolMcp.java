package io.github.jungm.crema.it.protocol.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.ws.rs.ApplicationPath;
import org.mcpjava.server.Icons;

/** The default MCP Server. */
@ApplicationPath("mcp")
@McpServerInfo(title = "Protocol IT", version = "1.2.3", description = "Crema protocol integration tests",
        instructions = "Use the shop tools.", websiteUrl = "https://example.com/crema")
@Icons(iconProvider = AppIcons.class)
public class ProtocolMcp extends McpApplication {
}
