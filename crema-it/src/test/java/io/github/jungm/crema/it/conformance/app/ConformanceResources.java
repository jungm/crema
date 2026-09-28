package io.github.jungm.crema.it.conformance.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;

/** The resource fixtures of the conformance suite. */
@ApplicationScoped
public class ConformanceResources {

    public record TemplateData(String id, boolean templateTest, String data) {
    }

    @Resource(uri = "test://static-text", name = "static_text", description = "A static text resource",
            mimeType = "text/plain")
    public String staticText() {
        return "This is the content of the static text resource.";
    }

    @Resource(uri = "test://static-binary", name = "static_binary", description = "A static binary resource",
            mimeType = "image/png")
    public byte[] staticBinary() {
        return ConformanceTools.PNG;
    }

    @ResourceTemplate(uriTemplate = "test://template/{id}/data", name = "template_data",
            description = "A resource template", mimeType = "application/json")
    public TemplateData template(String id) {
        return new TemplateData(id, true, "Data for ID: " + id);
    }
}
