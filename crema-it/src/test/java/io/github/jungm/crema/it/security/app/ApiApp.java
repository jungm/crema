package io.github.jungm.crema.it.security.app;

import jakarta.annotation.security.DeclareRoles;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.auth.LoginConfig;

/**
 * The application's ordinary MP-JWT protected REST API next to the MCP endpoint. It relies on JAX-RS scanning,
 * as most applications do, so the Runtime's own providers (such as its {@code @RolesAllowed} enforcement) apply.
 */
@ApplicationPath("api")
@LoginConfig(authMethod = "MP-JWT")
@DeclareRoles("user")
public class ApiApp extends Application {
}
