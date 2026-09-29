package io.github.jungm.crema.it.security.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ApplicationPath;

/**
 * A protected MCP Server with HTTP Basic authentication against users in MicroProfile Config.
 */
@ApplicationPath("basic")
@McpServerInfo(name = "basic")
@RolesAllowed("user")
public class BasicMcp extends McpApplication {
}
