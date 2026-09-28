package io.github.jungm.crema.it.security.app;

import io.github.jungm.crema.McpApplication;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ApplicationPath;

/**
 * The protected default MCP Server: bearer tokens only, role {@code user} unless a Feature Method says otherwise.
 */
@ApplicationPath("mcp")
@RolesAllowed("user")
public class SecuredMcp extends McpApplication {
}
