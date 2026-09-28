package io.github.jungm.crema.it.protocol;

import io.github.jungm.crema.it.mcp.CremaLibs;
import io.github.jungm.crema.it.protocol.app.ProtocolMcp;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * The WAR for the protocol tests: two MCP Servers ({@code /mcp} and {@code /mcp/admin}) and Features on beans of
 * several scopes. The admin MCP Server declares no version, so it falls back to the manifest's
 * {@code Implementation-Version}.
 */
final class ProtocolWar {

    static final String BEANS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                   version="4.0" bean-discovery-mode="annotated">
            </beans>
            """;

    static final String MANIFEST_VERSION = "9.9.9";

    private ProtocolWar() {
    }

    static WebArchive create(String name) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name + ".war")
                .addPackage(ProtocolMcp.class.getPackage())
                .addAsWebInfResource(new StringAsset(BEANS_XML), "beans.xml")
                .setManifest(new StringAsset("Manifest-Version: 1.0\nImplementation-Version: " + MANIFEST_VERSION
                        + "\n"));
        return CremaLibs.addTo(war);
    }
}
