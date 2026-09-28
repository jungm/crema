package io.github.jungm.crema.it.deployment.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.tools.Tool;

@ApplicationScoped
public class DuplicateB {
    @Tool(name = "duplicate", description = "Second")
    public String second() {
        return "b";
    }
}
