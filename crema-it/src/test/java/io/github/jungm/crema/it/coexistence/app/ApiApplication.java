package io.github.jungm.crema.it.coexistence.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** The application's own JAX-RS application, which scans the WAR for resources and providers. */
@ApplicationPath("api")
public class ApiApplication extends Application {
}
