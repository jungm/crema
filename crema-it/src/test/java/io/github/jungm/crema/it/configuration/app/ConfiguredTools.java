package io.github.jungm.crema.it.configuration.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.tools.Tool;

@ApplicationScoped
public class ConfiguredTools {

    @Tool(description = "Answers pong")
    public String ping() {
        return "pong";
    }
}
