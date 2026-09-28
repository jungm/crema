package io.github.jungm.crema.it.deployment.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.tools.Tool;

@ApplicationScoped
public class DuplicateA {
    @Tool(name = "duplicate", description = "First")
    public String first() {
        return "a";
    }
}
