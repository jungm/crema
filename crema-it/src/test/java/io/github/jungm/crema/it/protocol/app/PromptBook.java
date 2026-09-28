package io.github.jungm.crema.it.protocol.app;

import java.util.List;

import jakarta.enterprise.context.RequestScoped;
import org.mcpjava.server.Role;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.prompts.PromptResponse;

/** Prompts, on a request scoped bean. */
@RequestScoped
public class PromptBook {

    public record Message(Role role, ContentBlock content) implements PromptMessage {
    }

    @Prompt(title = "Code review", description = "Asks for a code review")
    public String review(@PromptArg(description = "The code") String code,
            @PromptArg(title = "Language", required = false, defaultValue = "java") String language) {
        return "Review this " + language + " code: " + code;
    }

    @Prompt(description = "A short conversation")
    public List<PromptMessage> conversation() {
        return List.of(new Message(Role.USER, TextContent.of("Hi")),
                new Message(Role.ASSISTANT, TextContent.of("Hello, how can I help?")));
    }

    @Prompt(description = "A briefing with an embedded resource")
    public PromptResponse briefing(String topic) {
        return PromptResponse.builder().setDescription("Briefing on " + topic)
                .addMessage(Role.USER, TextContent.of("Brief me on " + topic))
                .addMessage(Role.USER, EmbeddedResource.builder("notes on " + topic, "app://notes/" + topic)
                        .setMimeType("text/plain").build())
                .build();
    }

    @Prompt(description = "Fails")
    public String broken() {
        throw new IllegalStateException("secret internal detail");
    }
}
