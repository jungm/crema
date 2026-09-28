package io.github.jungm.crema.it.security.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * The application's own REST API next to the MCP Servers, without MP-JWT. It relies on JAX-RS scanning, as most
 * applications do.
 */
@ApplicationPath("api")
public class ApiApp extends Application {
}
