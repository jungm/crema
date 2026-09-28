package io.github.jungm.crema.it.security;

import java.io.File;
import java.net.URISyntaxException;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.importer.ExplodedImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.mcpjava.server.tools.Tool;

import com.nimbusds.jose.JWSObject;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.it.security.app.ApiApp;
import io.github.jungm.crema.it.security.app.ApiResource;
import io.github.jungm.crema.it.security.app.OidcConfig;
import io.github.jungm.crema.it.security.app.OpenFeatures;
import io.github.jungm.crema.it.security.app.OpenMcp;
import io.github.jungm.crema.it.security.app.SecuredFeatures;
import io.github.jungm.crema.it.security.app.SecuredMcp;
import io.github.jungm.crema.it.security.app.UiServlet;

/**
 * The WAR for the security tests: the protected MCP Server at {@code /mcp}, an open one at {@code /open}, a JAX-RS
 * API at {@code /api}, and a servlet at {@code /ui} protected by Jakarta Security's OpenID Connect mechanism. Crema,
 * Nimbus and the MCP server API are in {@code WEB-INF/lib}, as in a real application.
 */
final class SecurityWar {

    private SecurityWar() {
    }

    /**
     * @param issuer the fake Authorization Server's issuer, trusted by Crema and by the OpenID Connect mechanism
     */
    static WebArchive create(String name, String issuer) {
        String config = String.join("\n",
                "crema.default-server.issuer=" + issuer,
                "it.oidc.provider-uri=" + issuer,
                "");
        return ShrinkWrap.create(WebArchive.class, name + ".war")
                .addClasses(SecuredMcp.class, OpenMcp.class, SecuredFeatures.class, OpenFeatures.class,
                        ApiApp.class, ApiResource.class, UiServlet.class, OidcConfig.class)
                .addAsResource(new StringAsset(config), "META-INF/microprofile-config.properties")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsLibrary(library(McpApplication.class, "crema.jar"))
                .addAsLibrary(library(Tool.class, "mcp-server-api.jar"))
                .addAsLibrary(library(JWSObject.class, "nimbus-jose-jwt.jar"));
    }

    /**
     * The JAR on the test class path that holds a class; a class directory (a reactor build before
     * {@code package}) is packed into one.
     */
    private static JavaArchive library(Class<?> type, String name) {
        File location;
        try {
            location = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        if (location.isDirectory()) {
            return ShrinkWrap.create(ExplodedImporter.class, name).importDirectory(location).as(JavaArchive.class);
        }
        return ShrinkWrap.createFromZipFile(JavaArchive.class, location);
    }
}
