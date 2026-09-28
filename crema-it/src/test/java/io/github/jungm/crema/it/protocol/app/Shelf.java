package io.github.jungm.crema.it.protocol.app;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.enterprise.context.Dependent;
import org.mcpjava.server.Role;
import org.mcpjava.server.resources.BlobResourceContents;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.resources.TextResourceContents;

/** Resources and Resource Templates, on a dependent bean. */
@Dependent
public class Shelf {

    @Resource(uri = "app://readme", description = "The read-me", mimeType = "text/markdown",
            annotations = @Resource.Annotations(audience = Role.USER, priority = 0.5,
                    lastModified = "2026-07-28T10:00:00Z"))
    public String readme() {
        return "# Shop";
    }

    @Resource(uri = "app://logo", mimeType = "image/png", size = 70)
    public byte[] logo() {
        return ShopTools.PNG;
    }

    @Resource(uri = "app://config", title = "Configuration")
    public Model.Config config() {
        return new Model.Config("eu", true, List.of("a", "b"));
    }

    @Resource(uri = "app://bundle")
    public ResourceResponse bundle() {
        return ResourceResponse.builder()
                .addContents(TextResourceContents.builder("app://bundle#text", "text part").setMimeType("text/plain")
                        .build())
                .addContents(BlobResourceContents.of("app://bundle#blob", "blob".getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    @Resource(uri = "app://gone")
    public String gone() {
        return null;
    }

    @Resource(uri = "app://broken")
    public String broken() {
        throw new IllegalStateException("secret internal detail");
    }

    @ResourceTemplate(uriTemplate = "app://orders/{id}", name = "order", description = "One order",
            mimeType = "application/json")
    public Model.Order order(@ResourceTemplateArg(name = "id") String id) {
        return new Model.Order("customer-" + id, List.of());
    }

    @ResourceTemplate(uriTemplate = "app://users/{user}/files/{file}", name = "user_file")
    public String userFile(String user, String file) {
        return user + ":" + file;
    }
}
