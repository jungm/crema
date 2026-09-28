package io.github.jungm.crema.internal.spi;

import org.mcpjava.server.Icon;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.AudioContent;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.ResourceLink;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.BlobResourceContents;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.TextResourceContents;
import org.mcpjava.server.spi.McpServerSPI;
import org.mcpjava.server.tools.ToolResponse;

/**
 * Crema's {@link McpServerSPI}, registered through {@code META-INF/services}. All values it builds are
 * immutable.
 */
public class CremaSpi implements McpServerSPI {

    @Override
    public CompletionResult.Builder completeResultBuilder() {
        return new CompletionResultImpl.Builder();
    }

    @Override
    public TextContent.Builder textContentBuilder(String text) {
        return new TextContentImpl.Builder(text);
    }

    @Override
    public AudioContent.Builder audioContentBuilder(byte[] data, String mimeType) {
        return new AudioContentImpl.Builder(data, mimeType);
    }

    @Override
    public ImageContent.Builder imageContentBuilder(byte[] data, String mimeType) {
        return new ImageContentImpl.Builder(data, mimeType);
    }

    @Override
    public EmbeddedResource.Builder textEmbeddedResourceBuilder(String text, String uri) {
        return EmbeddedResourceImpl.Builder.text(text, uri);
    }

    @Override
    public EmbeddedResource.Builder blobEmbeddedResourceBuilder(byte[] data, String uri) {
        return EmbeddedResourceImpl.Builder.blob(data, uri);
    }

    @Override
    public ResourceLink.Builder resourceLinkBuilder(String name, String uri) {
        return new ResourceLinkImpl.Builder(name, uri);
    }

    @Override
    public Annotations.Builder annotationsBuilder() {
        return new AnnotationsImpl.Builder();
    }

    @Override
    public PromptResponse.Builder promptResponseBuilder() {
        return new PromptResponseImpl.Builder();
    }

    @Override
    public ResourceResponse.Builder resourceResponseBuilder() {
        return new ResourceResponseImpl.Builder();
    }

    @Override
    public TextResourceContents.Builder textResourceContentsBuilder(String uri, String text) {
        return new TextResourceContentsImpl.Builder(uri, text);
    }

    @Override
    public BlobResourceContents.Builder blobResourceContentsBuilder(String uri, byte[] data) {
        return new BlobResourceContentsImpl.Builder(uri, data);
    }

    @Override
    public ToolResponse.Builder toolResponseBuilder() {
        return new ToolResponseImpl.Builder();
    }

    @Override
    public Icon.Builder iconBuilder(String uri) {
        return new IconImpl.Builder(uri);
    }
}
