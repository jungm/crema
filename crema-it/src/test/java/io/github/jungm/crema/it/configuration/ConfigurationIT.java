package io.github.jungm.crema.it.configuration;

import io.github.jungm.crema.it.configuration.app.ConfiguredMcp;
import io.github.jungm.crema.it.mcp.CremaLibs;
import io.github.jungm.crema.it.mcp.Exchange;
import io.github.jungm.crema.it.mcp.McpClient;
import jakarta.json.JsonObject;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MicroProfile Config of the Runtime, read from the WAR's {@code META-INF/microprofile-config.properties}:
 * per-server keys override {@code @McpServerInfo}, and the global keys {@code crema.origin.allowed} and
 * {@code crema.cache.list-ttl-ms} apply.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class ConfigurationIT {

    private static final String ALLOWED_ORIGIN = "https://allowed.example.com";

    @ArquillianResource
    private URL base;

    private McpClient mcp;

    @Deployment(testable = false)
    static WebArchive deployment() {
        String config = String.join("\n",
                "crema.default-server.title=Configured title",
                "crema.default-server.version=2.0.0",
                "crema.default-server.instructions=Configured instructions",
                "crema.origin.allowed=https://other.example.com, " + ALLOWED_ORIGIN,
                "crema.cache.list-ttl-ms=1234",
                "");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "configuration.war")
                .addPackage(ConfiguredMcp.class.getPackage())
                .addAsResource(new StringAsset(config), "META-INF/microprofile-config.properties")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return CremaLibs.addTo(war);
    }

    @BeforeEach
    void client() {
        mcp = McpClient.at(base, "mcp");
    }

    @Test
    void configOverridesServerInfo() {
        JsonObject result = mcp.request("server/discover").result();
        assertEquals("Configured instructions", result.getString("instructions"));
        JsonObject info = result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo");
        assertEquals("Configured title", info.getString("title"));
        assertEquals("2.0.0", info.getString("version"));
        assertEquals("Annotated description", info.getString("description"));
    }

    @Test
    void listTtlComesFromConfig() {
        assertEquals(1234, mcp.request("server/discover").result().getJsonNumber("ttlMs").longValue());
        JsonObject tools = mcp.request("tools/list").result();
        assertEquals(1234, tools.getJsonNumber("ttlMs").longValue());
        assertEquals("ping", tools.getJsonArray("tools").getJsonObject(0).getString("name"));
    }

    @Test
    void allowedOriginsComeFromConfig() {
        mcp.post("tools/list").header("Origin", ALLOWED_ORIGIN).send().result();
        mcp.post("tools/list").header("Origin", "https://other.example.com").send().result();
        Exchange foreign = mcp.post("tools/list").header("Origin", "https://evil.example.com").send();
        assertEquals(403, foreign.status(), foreign::describe);
        assertFalse(foreign.message().containsKey("id"));
    }
}
