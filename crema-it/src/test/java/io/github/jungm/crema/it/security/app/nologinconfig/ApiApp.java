package io.github.jungm.crema.it.security.app.nologinconfig;

import jakarta.annotation.security.DeclareRoles;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** {@link io.github.jungm.crema.it.security.app.ApiApp} without {@code @LoginConfig}, for Liberty. */
@ApplicationPath("api")
@DeclareRoles("user")
public class ApiApp extends Application {
}
