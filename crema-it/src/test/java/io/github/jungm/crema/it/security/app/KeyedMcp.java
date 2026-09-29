package io.github.jungm.crema.it.security.app;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ApplicationPath;

/**
 * A protected MCP Server whose callers {@link KeyedAuthenticator} authenticates by API key, without OAuth.
 */
@ApplicationPath("keyed")
@McpServerInfo(name = "keyed")
@RolesAllowed("user")
public class KeyedMcp extends McpApplication {
}
