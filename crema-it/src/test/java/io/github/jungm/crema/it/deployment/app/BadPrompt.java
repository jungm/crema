package io.github.jungm.crema.it.deployment.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.prompts.Prompt;

@ApplicationScoped
public class BadPrompt {
    @Prompt(description = "Returns a type prompts can't return")
    public Integer number() {
        return 1;
    }
}
