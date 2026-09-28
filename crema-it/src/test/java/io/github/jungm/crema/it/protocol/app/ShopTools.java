package io.github.jungm.crema.it.protocol.app;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mcpjava.server.McpException;
import org.mcpjava.server.McpRequest;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.MetaField;
import org.mcpjava.server.Role;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.AudioContent;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.ResourceLink;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

/** Tools of the default MCP Server, on an application scoped bean with injected collaborators. */
@ApplicationScoped
public class ShopTools {

    static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==");

    @Inject
    CallLog calls;

    @Inject
    RequestState requestState;

    @Tool(description = "Adds two integers")
    public int add(@ToolArg(description = "First summand") int a, @ToolArg(description = "Second summand") int b) {
        return a + b;
    }

    @Tool(title = "Greeter", description = "Greets someone")
    public String greet(@ToolArg(description = "Who to greet") String name,
            @ToolArg(required = false, defaultValue = "Hello") String greeting) {
        return greeting + ", " + name + "!";
    }

    @Tool(name = "place_order", description = "Places an order", structuredContent = true)
    public Model.Receipt placeOrder(@ToolArg(description = "The order") Model.Order order,
            @ToolArg(required = false, defaultValue = "NORMAL") Model.Priority priority) {
        BigDecimal total = order.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Model.Receipt("order-" + calls.next(), total, priority, order.items().size());
    }

    @Tool(description = "Current temperature, rendered by a ContentEncoder")
    public Model.Temperature temperature(String city) {
        return new Model.Temperature(21.5);
    }

    @Tool(description = "Current humidity, rendered by the more general ContentEncoder")
    public Model.Humidity humidity() {
        return new Model.Humidity(40);
    }

    @Tool(description = "Stock per SKU, as JSON text")
    public Map<String, Integer> inventory() {
        return new TreeMap<>(Map.of("apple", 3, "pear", 0));
    }

    @Tool(description = "Fails with an unexpected exception")
    public String boom() {
        throw new IllegalStateException("secret internal detail");
    }

    @Tool(description = "Fails with a message for the model")
    public String refuse() {
        throw new McpException("Refusing: the warehouse is closed");
    }

    @Tool(description = "Does nothing")
    public void noop() {
    }

    @Tool(description = "Echoes asynchronously")
    public CompletionStage<String> echo(String text) {
        return CompletableFuture.supplyAsync(() -> "echo: " + text);
    }

    @Tool(description = "Returns several content blocks")
    public List<ContentBlock> mixed() {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextContent.builder("some text")
                .setAnnotations(Annotations.builder().setAudience(Role.USER).setPriority(0.5).build()).build());
        blocks.add(ImageContent.of(PNG, "image/png"));
        blocks.add(AudioContent.of("RIFF".getBytes(StandardCharsets.US_ASCII), "audio/wav"));
        blocks.add(ResourceLink.builder("readme", "app://readme").setMimeType("text/markdown").build());
        blocks.add(EmbeddedResource.builder("embedded text", "app://embedded").setMimeType("text/plain").build());
        return blocks;
    }

    @Tool(description = "Returns strings as text blocks")
    public List<String> lines() {
        return List.of("first", "second");
    }

    @Tool(description = "Builds its own ToolResponse")
    public ToolResponse custom() {
        return ToolResponse.builder().addTextContent("custom").setStructuredContent(Map.of("ok", true))
                .putMetadata("com.example/trace", "abc").build();
    }

    @Tool(description = "Carries metadata and annotations",
            annotations = @Tool.Annotations(title = "Stock level", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @MetaField(prefix = "com.example/", name = "owner", value = "team-x")
    @MetaField(name = "cost", type = MetaField.Type.INT, value = "3")
    @MetaField(prefix = "com.example/", name = "limits", type = MetaField.Type.JSON, value = "{\"max\":10}")
    public int stock(String sku) {
        return sku.length();
    }

    @Tool(description = "Optional arguments")
    public String optionals(Optional<String> nickname, OptionalInt age) {
        return nickname.orElse("anonymous") + "/" + (age.isPresent() ? age.getAsInt() : -1);
    }

    @Tool(description = "Describes the request")
    public Model.RequestView whoami(McpRequest request) {
        return new Model.RequestView(String.valueOf(request.id()), request.protocolVersion(),
                request.clientInfo().name(), request.clientInfo().version(), request.sessionId().isPresent(),
                new ArrayList<>(new java.util.TreeSet<>(request.metadata().keySet())),
                new ArrayList<>(new java.util.TreeSet<>(request.rawClientCapabilities().keySet())));
    }

    @Tool(description = "Uses the request scoped bean twice")
    public int scoped() {
        requestState.use();
        return requestState.use();
    }

    @Tool(description = "Counts down, reporting progress")
    public String countdown(int from, Progress progress) {
        for (int i = 1; i <= from && progress.token().isPresent(); i++) {
            CompletableFuture<Void> sent = progress.notificationBuilder().setProgress(i).setTotal(from)
                    .setMessage("step " + i).build().send();
            sent.join();
        }
        return "lift-off";
    }

    @McpServer(McpServer.DEFAULT)
    @McpServer("admin")
    @Tool(description = "Available on both MCP Servers")
    public String health() {
        return "ok";
    }
}
