package io.github.jungm.crema.it.security.app;

import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.ApplicationPath;
import org.eclipse.microprofile.auth.LoginConfig;

/**
 * Stands in for an {@code McpApplication} subclass: MP-JWT login config, no role constraints, own 401 handling.
 * {@code @Dependent} keeps Liberty from proxying the class, which the {@code final} methods of the base prevent.
 */
@ApplicationPath("mcp")
@Dependent
@LoginConfig(authMethod = "MP-JWT")
public class McpApp extends McpApplicationBase {
}
