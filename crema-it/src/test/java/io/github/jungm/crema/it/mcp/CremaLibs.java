package io.github.jungm.crema.it.mcp;

import io.github.jungm.crema.McpApplication;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.importer.ExplodedImporter;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.mcpjava.server.McpServer;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Crema and its runtime dependencies as {@code WEB-INF/lib} JARs, taken from the test class path (a JAR, or the
 * {@code target/classes} directory of the reactor module).
 */
public final class CremaLibs {

    /** Classes from each runtime dependency of Crema; optional ones are skipped when absent. */
    private static final List<String> MARKERS = List.of(McpApplication.class.getName(), McpServer.class.getName(),
            "com.nimbusds.jose.JWSObject");

    private CremaLibs() {
    }

    public static WebArchive addTo(WebArchive war) {
        for (JavaArchive lib : libraries()) {
            war.addAsLibrary(lib);
        }
        return war;
    }

    public static List<JavaArchive> libraries() {
        List<JavaArchive> libs = new ArrayList<>();
        for (String marker : MARKERS) {
            Class<?> type;
            try {
                type = Class.forName(marker, false, CremaLibs.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }
            File location;
            try {
                location = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
            String name = location.isDirectory() ? "crema.jar" : location.getName();
            JavaArchive jar = ShrinkWrap.create(JavaArchive.class, name);
            if (location.isDirectory()) {
                jar.as(ExplodedImporter.class).importDirectory(location);
            } else {
                jar.as(ZipImporter.class).importFrom(location);
            }
            libs.add(jar);
        }
        return libs;
    }
}
