package io.github.jungm.crema.it.conformance.app;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptResponse;

/** The prompt and completion fixtures of the conformance suite. */
@ApplicationScoped
public class ConformancePrompts {

    @Prompt(name = "test_simple_prompt", description = "A simple prompt")
    public String simplePrompt() {
        return "This is a simple prompt for testing.";
    }

    @Prompt(name = "test_prompt_with_arguments", description = "A prompt with arguments")
    public String promptWithArguments(@PromptArg(name = "arg1", description = "First argument") String arg1,
            @PromptArg(name = "arg2", description = "Second argument") String arg2) {
        return "Prompt with arguments: arg1='" + arg1 + "', arg2='" + arg2 + "'";
    }

    @Prompt(name = "test_prompt_with_embedded_resource", description = "A prompt with an embedded resource")
    public PromptResponse promptWithEmbeddedResource(
            @PromptArg(name = "resourceUri", description = "URI of the resource to embed") String resourceUri) {
        return PromptResponse.builder()
                .addMessage(Role.USER, EmbeddedResource.builder("Embedded resource content for testing.",
                        resourceUri).setMimeType("text/plain").build())
                .addMessage(Role.USER, TextContent.of("Please process the embedded resource above."))
                .build();
    }

    @Prompt(name = "test_prompt_with_image", description = "A prompt with an image")
    public PromptResponse promptWithImage() {
        return PromptResponse.builder()
                .addMessage(Role.USER, ImageContent.of(ConformanceTools.PNG, "image/png"))
                .addMessage(Role.USER, TextContent.of("Please analyze the image above."))
                .build();
    }

    @CompletePrompt("test_prompt_with_arguments")
    public List<String> completeArg1(@CompleteArg(name = "arg1") String value) {
        return List.of("test", "testValue1", "testing").stream().filter(v -> v.startsWith(value)).toList();
    }
}
