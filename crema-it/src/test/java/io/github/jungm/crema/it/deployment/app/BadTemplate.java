package io.github.jungm.crema.it.deployment.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.resources.ResourceTemplate;

@ApplicationScoped
public class BadTemplate {
    @ResourceTemplate(uriTemplate = "app://items/{id}")
    public String item(String name) {
        return name;
    }
}
