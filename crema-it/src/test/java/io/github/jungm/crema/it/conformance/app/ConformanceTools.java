package io.github.jungm.crema.it.conformance.app;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.McpException;
import org.mcpjava.server.McpRequest;
import org.mcpjava.server.content.AudioContent;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.tools.Tool;

/**
 * The tool fixtures of the conformance suite (protocol-notes "Test tooling"), named after the reference server
 * {@code everything-server.ts}.
 */
@ApplicationScoped
public class ConformanceTools {

    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==");
    static final byte[] WAV = Base64.getDecoder().decode(
            "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAA=");

    @Tool(name = "test_simple_text", description = "Returns simple text")
    public String simpleText() {
        return "This is a simple text response for testing.";
    }

    @Tool(name = "test_image_content", description = "Returns an image")
    public ImageContent imageContent() {
        return ImageContent.of(PNG, "image/png");
    }

    @Tool(name = "test_audio_content", description = "Returns audio")
    public AudioContent audioContent() {
        return AudioContent.of(WAV, "audio/wav");
    }

    @Tool(name = "test_embedded_resource", description = "Returns an embedded resource")
    public EmbeddedResource embeddedResource() {
        return EmbeddedResource.builder("This is an embedded resource content.", "test://embedded-resource")
                .setMimeType("text/plain").build();
    }

    @Tool(name = "test_multiple_content_types", description = "Returns text, an image and a resource")
    public List<ContentBlock> multipleContentTypes() {
        return List.of(TextContent.of("Multiple content types test:"), ImageContent.of(PNG, "image/png"),
                EmbeddedResource.builder("{\"test\":\"data\",\"value\":123}", "test://mixed-content-resource")
                        .setMimeType("application/json").build());
    }

    @Tool(name = "test_error_handling", description = "Always fails")
    public String errorHandling() {
        throw new McpException("This tool intentionally returns an error for testing");
    }

    @Tool(name = "test_tool_with_progress", description = "Reports progress")
    public String toolWithProgress(Progress progress) {
        if (progress.token().isPresent()) {
            for (int i = 0; i <= 100; i += 50) {
                CompletableFuture<Void> sent = progress.notificationBuilder().setProgress(i).setTotal(100)
                        .setMessage("Completed step " + i + " of 100").build().send();
                sent.join();
            }
        }
        return "Progress test completed";
    }

    /**
     * Should raise {@code -32021} without the {@code sampling} capability, which Crema can't express; see the
     * conformance baseline.
     */
    @Tool(name = "test_missing_capability", description = "Requires the sampling client capability")
    public String missingCapability(McpRequest request) {
        if (!request.rawClientCapabilities().containsKey("sampling")) {
            throw new McpException("This tool requires the sampling client capability");
        }
        return "Success";
    }

    @Tool(name = "test_streaming_elicitation", description = "Returns a plain result")
    public String streamingElicitation() {
        return "No elicitation: Crema sends no requests to clients";
    }

    @Tool(name = "test_logging_tool", description = "Returns a plain result without logging")
    public String loggingTool() {
        return "No log messages: Crema never sends notifications/message";
    }
}
