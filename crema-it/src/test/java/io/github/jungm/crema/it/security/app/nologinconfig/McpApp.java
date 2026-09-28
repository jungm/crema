package io.github.jungm.crema.it.security.app.nologinconfig;

import io.github.jungm.crema.it.security.app.McpApplicationBase;
import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.ApplicationPath;

/**
 * {@link io.github.jungm.crema.it.security.app.McpApp} without {@code @LoginConfig}, for Liberty: Liberty turns
 * {@code @LoginConfig} into a login configuration and rejects a module that has both a login configuration and a
 * Jakarta Security mechanism (CWWKS1931E). Liberty's MP-JWT applies without it
 * ({@code mpJwt ignoreApplicationAuthMethod="true"} is the default).
 */
@ApplicationPath("mcp")
@Dependent
public class McpApp extends McpApplicationBase {
}
