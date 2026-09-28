package io.github.jungm.crema.internal.http;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;

import org.mcpjava.server.McpException;
import org.mcpjava.server.McpRequest;
import org.mcpjava.server.MetaField;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.completion.CompleteResourceTemplate;
import org.mcpjava.server.completion.CompletionContext;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

/**
 * Features shaped like the conformance suite's fixtures, plus error cases.
 */
final class Fixtures {

    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    private Fixtures() {
    }

    public record Weather(double temperature) {
    }

    public static class Tools {

        @Tool(description = "Simple text")
        public String test_simple_text() {
            return "This is a simple text response for testing.";
        }

        @Tool(description = "Image")
        public ImageContent test_image_content() {
            return ImageContent.builder(Base64.getDecoder().decode(PNG), "image/png").build();
        }

        @Tool(description = "Mixed")
        public List<Object> test_multiple_content_types() {
            return List.of("Multiple content types test:", test_image_content(),
                    EmbeddedResource.builder("{\"test\":\"data\",\"value\":123}", "test://mixed-content-resource")
                            .setMimeType("application/json").build());
        }

        @Tool(description = "Fails")
        public String test_error_handling() {
            throw new IllegalStateException("secret database password");
        }

        @Tool(description = "Fails visibly")
        public String visible_error() {
            throw new McpException("Order 42 doesn't exist");
        }

        @Tool(description = "Progress")
        public String test_tool_with_progress(Progress progress) throws Exception {
            for (int i = 0; i <= 100; i += 50) {
                progress.notificationBuilder().setProgress(i).setTotal(100).build().<CompletableFuture<Void>>send()
                        .get();
            }
            return "done";
        }

        @Tool(description = "Adds")
        public int add(int a, @ToolArg(defaultValue = "1") int b) {
            return a + b;
        }

        @Tool(description = "Greets")
        public String greet(Optional<String> name) {
            return "Hello " + name.orElse("you");
        }

        @Tool(description = "Weather", structuredContent = true)
        public CompletionStage<Weather> weather(String city) {
            return CompletableFuture.completedFuture(new Weather(22.5));
        }

        @Tool(description = "Nothing")
        public void nothing() {
        }

        @Tool(description = "Throws an Error")
        public String fatal(Progress progress) {
            throw new LinkageError("secret linkage problem");
        }

        @Tool(description = "Who calls")
        public String whoami(McpRequest request) {
            return request.clientInfo().name() + " " + request.id() + " " + request.metadata();
        }
    }

    public static class Resources {

        @Resource(uri = "test://static-text", mimeType = "text/plain", description = "Text")
        @MetaField(prefix = "com.example/", name = "owner", value = "team-x")
        public String text() {
            return "This is the content of the static text resource.";
        }

        @Resource(uri = "test://static-binary", mimeType = "image/png")
        public byte[] binary() {
            return Base64.getDecoder().decode(PNG);
        }

        @Resource(uri = "test://broken")
        public String broken() {
            throw new IllegalStateException("secret");
        }

        @ResourceTemplate(uriTemplate = "test://template/{id}/data", mimeType = "application/json")
        public Map<String, Object> template(String id) {
            return Map.of("id", id, "templateTest", true);
        }

        @ResourceTemplate(uriTemplate = "test://maybe/{id}")
        public String maybe(String id) {
            return id.equals("there") ? "found" : null;
        }

        @CompleteResourceTemplate("template")
        public List<String> completeId(String id, CompletionContext context) {
            return List.of(id + "1", id + "2");
        }
    }

    public static class Prompts {

        @Prompt(description = "Simple")
        public String test_simple_prompt() {
            return "Hello";
        }

        @Prompt(description = "With arguments")
        public String test_prompt_with_arguments(@PromptArg(description = "First") String arg1,
                @PromptArg(required = false) String arg2, @PromptArg(defaultValue = "2") int times) {
            return "Prompt with arguments: arg1='" + arg1 + "', arg2='" + arg2 + "' x" + times;
        }

        @Prompt(description = "Assistant")
        public PromptResponse assistant() {
            return PromptResponse.builder().setDescription("desc").addMessage(Role.ASSISTANT, TextContent.of("A"))
                    .build();
        }

        @Prompt(description = "Fails")
        public String failing() {
            throw new McpException("Visible prompt failure");
        }

        @CompletePrompt("test_prompt_with_arguments")
        public List<String> completeArg1(@CompleteArg(name = "arg1") String value, CompletionContext context) {
            String prefix = context.getArgument("arg2").orElse("");
            return IntStream.range(0, 150).mapToObj(i -> prefix + value + i).toList();
        }
    }
}
