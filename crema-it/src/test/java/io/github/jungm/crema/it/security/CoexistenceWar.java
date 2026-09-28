package io.github.jungm.crema.it.security;

import io.github.jungm.crema.it.security.app.ApiApp;
import io.github.jungm.crema.it.security.app.ApiResource;
import io.github.jungm.crema.it.security.app.AppResponseFilter;
import io.github.jungm.crema.it.security.app.McpApp;
import io.github.jungm.crema.it.security.app.McpApplicationBase;
import io.github.jungm.crema.it.security.app.OidcConfig;
import io.github.jungm.crema.it.security.app.UiServlet;
import io.github.jungm.crema.it.security.app.WhoAmIResource;
import io.github.jungm.crema.it.security.app.ee11.PathMechanismHandler;
import io.github.jungm.crema.it.support.Jwts;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * The WAR for the security coexistence tests: an MCP-style JAX-RS application at {@code /mcp}, an MP-JWT protected
 * JAX-RS application at {@code /api}, and a servlet at {@code /ui} protected by Jakarta Security's OpenID Connect
 * mechanism. Runtime-specific parts are switched by system properties that the Maven profiles set.
 */
final class CoexistenceWar {

    /**
     * RESTEasy (WildFly) enforces {@code @RolesAllowed} on plain JAX-RS resources only with this switch; other
     * Runtimes ignore the parameter.
     */
    private static final String WEB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                     version="6.0">
                <context-param>
                    <param-name>resteasy.role.based.security</param-name>
                    <param-value>true</param-value>
                </context-param>
            </web-app>
            """;

    private CoexistenceWar() {
    }

    /** Whether the JAX-RS applications carry {@code @LoginConfig(authMethod = "MP-JWT")} on this Runtime. */
    static boolean loginConfig() {
        return Boolean.parseBoolean(System.getProperty("crema.it.login-config", "true"));
    }

    static WebArchive create(String name, boolean loginConfig, String oidcProviderUri) {
        String config = String.join("\n",
                "mp.jwt.verify.publickey=" + Jwts.publicKeyPem().replace("\n", "\\n"),
                "mp.jwt.verify.issuer=" + Jwts.ISSUER,
                "mp.jwt.verify.audiences=" + Jwts.MCP_AUDIENCE + "," + Jwts.API_AUDIENCE,
                "it.oidc.provider-uri=" + oidcProviderUri,
                "");
        WebArchive war = ShrinkWrap.create(WebArchive.class, name + ".war")
                .addClasses(McpApplicationBase.class, WhoAmIResource.class, ApiResource.class,
                        AppResponseFilter.class, UiServlet.class, OidcConfig.class)
                .addAsResource(new StringAsset(config), "META-INF/microprofile-config.properties")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .setWebXML(new StringAsset(WEB_XML));
        if (loginConfig) {
            war.addClasses(McpApp.class, ApiApp.class);
        } else {
            war.addClasses(io.github.jungm.crema.it.security.app.nologinconfig.McpApp.class,
                    io.github.jungm.crema.it.security.app.nologinconfig.ApiApp.class);
        }
        if (Boolean.getBoolean("crema.it.ee11-mechanism-handler")) {
            war.addClass(PathMechanismHandler.class);
        }
        return war;
    }
}
