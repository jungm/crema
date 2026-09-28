package io.github.jungm.crema.it.deployment.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.tools.Tool;

@ApplicationScoped
public class ValidTools {
    @Tool(description = "Valid")
    public String valid() {
        return "ok";
    }
}
