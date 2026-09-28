package io.github.jungm.crema.internal.invoke;

import java.util.List;

import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.BlobResourceContents;
import org.mcpjava.server.resources.ResourceContents;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.TextResourceContents;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.internal.bind.JsonbBridge;

/**
 * Converts the return values of Feature Methods and Completion Methods to responses.
 */
public final class ReturnConversion {

    private static final String JSON = "application/json";

    private ReturnConversion() {
    }

    /**
     * {@code String} becomes text content, a {@code ContentBlock} is used as is, a {@code List} of both becomes
     * multiple content blocks, a {@code ToolResponse} is used as is, and {@code null} becomes empty content. Any
     * other value is encoded by the most specific {@code ContentEncoder}, else serialized with JSON-B into one text
     * block. With {@code structuredContent}, the value's JSON also becomes the structured content.
     */
    public static ToolResponse tool(Object value, boolean structuredContent, ContentEncoders encoders,
            JsonbBridge jsonb) {
        if (value instanceof ToolResponse response) {
            return response;
        }
        ToolResponse.Builder response = ToolResponse.builder();
        if (value == null) {
            return response.build();
        }
        if (value instanceof String text) {
            response.addTextContent(text);
        } else if (value instanceof ContentBlock block) {
            response.addContent(block);
        } else if (value instanceof List<?> list && list.stream().allMatch(ReturnConversion::isContent)) {
            list.forEach(item -> response.addContent(item instanceof String text ? TextContent.of(text)
                    : (ContentBlock) item));
        } else {
            response.addContent(encoders.encode(value).orElseGet(() -> TextContent.of(jsonb.toJson(value))));
        }
        if (structuredContent) {
            response.setStructuredContent(jsonb.toJsonValue(value));
        }
        return response.build();
    }

    /**
     * {@code String} becomes text contents, {@code byte[]} blob contents, {@code ResourceContents}, a {@code List}
     * of them and {@code ResourceResponse} are used as is, and any other value is serialized with JSON-B into text
     * contents of the declared MIME type, else {@code application/json}. Generated contents carry the requested
     * {@code uri}. An empty {@code List} becomes a response without contents.
     *
     * @param mimeType the declared MIME type, or {@code null}
     */
    public static ResourceResponse resource(Object value, String uri, String mimeType, JsonbBridge jsonb) {
        if (value instanceof ResourceResponse response) {
            return response;
        }
        ResourceResponse.Builder response = ResourceResponse.builder();
        if (value instanceof ResourceContents contents) {
            response.addContents(contents);
        } else if (value instanceof List<?> list && list.stream().allMatch(ResourceContents.class::isInstance)) {
            list.forEach(item -> response.addContents((ResourceContents) item));
        } else if (value instanceof String text) {
            TextResourceContents.Builder contents = TextResourceContents.builder(uri, text);
            if (mimeType != null) {
                contents.setMimeType(mimeType);
            }
            response.addContents(contents.build());
        } else if (value instanceof byte[] data) {
            BlobResourceContents.Builder contents = BlobResourceContents.builder(uri, data);
            if (mimeType != null) {
                contents.setMimeType(mimeType);
            }
            response.addContents(contents.build());
        } else {
            response.addContents(TextResourceContents.builder(uri, jsonb.toJson(value))
                    .setMimeType(mimeType != null ? mimeType : JSON).build());
        }
        return response.build();
    }

    /**
     * {@code String} becomes one user text message; {@code PromptMessage}, a {@code List} of them and
     * {@code PromptResponse} are used as is.
     *
     * @throws IllegalStateException for {@code null}
     */
    public static PromptResponse prompt(Object value) {
        if (value instanceof PromptResponse response) {
            return response;
        }
        if (value instanceof String text) {
            return PromptResponse.of(Role.USER, TextContent.of(text));
        }
        PromptResponse.Builder response = PromptResponse.builder();
        if (value instanceof PromptMessage message) {
            response.addMessage(message.role(), message.content());
        } else if (value instanceof List<?> list) {
            list.forEach(item -> {
                PromptMessage message = (PromptMessage) item;
                response.addMessage(message.role(), message.content());
            });
        } else {
            throw new IllegalStateException("The Prompt method returned " + value);
        }
        return response.build();
    }

    /**
     * {@code String} becomes one value, a {@code List<String>} all values, {@code CompletionResult} is used as is,
     * and {@code null} means no values.
     */
    public static CompletionResult completion(Object value) {
        if (value instanceof CompletionResult result) {
            return result;
        }
        if (value instanceof String string) {
            return CompletionResult.newCompleteResult(List.of(string));
        }
        if (value instanceof List<?> list) {
            return CompletionResult.newCompleteResult(list.stream().map(String.class::cast).toList());
        }
        return CompletionResult.newCompleteResult(List.of());
    }

    private static boolean isContent(Object item) {
        return item instanceof String || item instanceof ContentBlock;
    }
}
