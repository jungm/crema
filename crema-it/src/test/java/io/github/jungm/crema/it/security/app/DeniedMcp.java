package io.github.jungm.crema.it.security.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.annotation.security.DenyAll;
import jakarta.ws.rs.ApplicationPath;

/**
 * A protected MCP Server that denies every Feature unless the Feature Method says otherwise.
 */
@ApplicationPath("denied")
@McpServerInfo(name = "denied")
@DenyAll
public class DeniedMcp extends McpApplication {
}
