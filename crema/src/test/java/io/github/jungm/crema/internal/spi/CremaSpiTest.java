package io.github.jungm.crema.internal.spi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mcpjava.server.Icon;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.AudioContent;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.EmbeddedResource;
import org.mcpjava.server.content.ImageContent;
import org.mcpjava.server.content.ResourceLink;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.BlobResourceContents;
import org.mcpjava.server.resources.ResourceContents;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.resources.TextResourceContents;
import org.mcpjava.server.spi.McpServerSPILoader;
import org.mcpjava.server.tools.ToolResponse;

/**
 * Exercises {@link CremaSpi} through the API's static factories, which find it with {@code ServiceLoader}.
 */
class CremaSpiTest {

    private static final byte[] DATA = { 1, 2, 3 };

    @Test
    void isLoadedByServiceLoader() {
        assertInstanceOf(CremaSpi.class, McpServerSPILoader.getSPI());
    }

    @Test
    void textContent() {
        TextContent plain = TextContent.of("hello");
        assertEquals("hello", plain.text());
        assertTrue(plain.annotations().isEmpty());
        assertTrue(plain.metadata().isEmpty());

        Annotations annotations = Annotations.builder().setPriority(0.5).build();
        TextContent full = TextContent.builder("hi")
                .setAnnotations(annotations)
                .putMetadata("com.example/key", 42)
                .build();
        assertEquals(Optional.of(annotations), full.annotations());
        assertEquals(Map.of("com.example/key", 42), full.metadata());
        assertThrows(NullPointerException.class, () -> TextContent.of(null));
    }

    @Test
    void imageContentCopiesData() {
        byte[] data = DATA.clone();
        ImageContent image = ImageContent.of(data, "image/png");
        data[0] = 9;
        assertArrayEquals(DATA, image.data());
        image.data()[0] = 9;
        assertArrayEquals(DATA, image.data());
        assertEquals("image/png", image.mimeType());
        assertTrue(image.annotations().isEmpty());

        byte[] builderData = DATA.clone();
        ImageContent.Builder builder = ImageContent.builder(builderData, "image/jpeg");
        builderData[0] = 9;
        ImageContent built = builder.setAnnotations(Annotations.builder().build()).putMetadata("k", "v").build();
        assertArrayEquals(DATA, built.data());
        assertTrue(built.annotations().isPresent());
        assertEquals(Map.of("k", "v"), built.metadata());
        assertEquals(ImageContent.of(DATA, "image/png"), image);
        assertEquals(ImageContent.of(DATA, "image/png").hashCode(), image.hashCode());
        assertThrows(NullPointerException.class, () -> ImageContent.of(null, "image/png"));
        assertThrows(NullPointerException.class, () -> ImageContent.of(DATA, null));
    }

    @Test
    void audioContentCopiesData() {
        byte[] data = DATA.clone();
        AudioContent audio = AudioContent.of(data, "audio/wav");
        data[0] = 9;
        assertArrayEquals(DATA, audio.data());
        audio.data()[0] = 9;
        assertArrayEquals(DATA, audio.data());
        assertEquals("audio/wav", audio.mimeType());

        AudioContent built = AudioContent.builder(DATA, "audio/ogg")
                .setAnnotations(Annotations.builder().setAudience(Role.USER).build())
                .setMetadata(Map.of("a", 1))
                .build();
        assertEquals(Optional.of(Set.of(Role.USER)), built.annotations().orElseThrow().audience());
        assertEquals(Map.of("a", 1), built.metadata());
        assertEquals(AudioContent.of(DATA, "audio/wav"), audio);
    }

    @Test
    void textEmbeddedResource() {
        EmbeddedResource embedded = EmbeddedResource.builder("text", "file:///a.txt")
                .setMimeType("text/plain")
                .putResourceMeta("com.example/r", "x")
                .putMetadata("com.example/e", "y")
                .setAnnotations(Annotations.builder().setPriority(1.0).build())
                .build();
        TextResourceContents contents = assertInstanceOf(TextResourceContents.class, embedded.resource());
        assertEquals("file:///a.txt", contents.uri());
        assertEquals("text", contents.text());
        assertEquals(Optional.of("text/plain"), contents.mimeType());
        assertEquals(Map.of("com.example/r", "x"), contents.metadata());
        assertEquals(Map.of("com.example/e", "y"), embedded.metadata());
        assertEquals(OptionalDouble.of(1.0), embedded.annotations().orElseThrow().priority());
    }

    @Test
    void blobEmbeddedResource() {
        byte[] data = DATA.clone();
        EmbeddedResource.Builder builder = EmbeddedResource.builder(data, "file:///a.bin");
        data[0] = 9;
        EmbeddedResource embedded = builder.build();
        BlobResourceContents contents = assertInstanceOf(BlobResourceContents.class, embedded.resource());
        assertArrayEquals(DATA, contents.blob());
        assertTrue(contents.mimeType().isEmpty());
        assertTrue(contents.metadata().isEmpty());
        assertTrue(embedded.annotations().isEmpty());
    }

    @Test
    void resourceLink() {
        ResourceLink minimal = ResourceLink.builder("main.rs", "file:///main.rs").build();
        assertEquals("main.rs", minimal.name());
        assertEquals("main.rs", minimal.title());
        assertEquals("file:///main.rs", minimal.uri());
        assertTrue(minimal.description().isEmpty());
        assertTrue(minimal.mimeType().isEmpty());
        assertTrue(minimal.annotations().isEmpty());
        assertEquals(OptionalLong.empty(), minimal.size());

        ResourceLink full = ResourceLink.builder("main.rs", "file:///main.rs")
                .setTitle("Main")
                .setDescription("Entry point")
                .setMimeType("text/x-rust")
                .setSize(42)
                .setAnnotations(Annotations.builder().build())
                .putMetadata("m", true)
                .build();
        assertEquals("Main", full.title());
        assertEquals(Optional.of("Entry point"), full.description());
        assertEquals(Optional.of("text/x-rust"), full.mimeType());
        assertEquals(OptionalLong.of(42), full.size());
        assertTrue(full.annotations().isPresent());
        assertEquals(Map.of("m", true), full.metadata());
        assertThrows(IllegalArgumentException.class, () -> ResourceLink.builder("n", "u").setSize(-1));
    }

    @Test
    void annotations() {
        Annotations empty = Annotations.builder().build();
        assertTrue(empty.audience().isEmpty());
        assertTrue(empty.priority().isEmpty());
        assertTrue(empty.lastModified().isEmpty());

        Instant now = Instant.parse("2025-05-03T14:30:00Z");
        Annotations full = Annotations.builder()
                .setAudience(Role.ASSISTANT, Role.USER, Role.USER)
                .setPriority(0.7)
                .setLastModified(now)
                .build();
        assertEquals(List.of(Role.USER, Role.ASSISTANT), new ArrayList<>(full.audience().orElseThrow()));
        assertEquals(OptionalDouble.of(0.7), full.priority());
        assertEquals(Optional.of(now), full.lastModified());
        assertThrows(UnsupportedOperationException.class, () -> full.audience().orElseThrow().add(Role.USER));

        Set<Role> roles = EnumSet.of(Role.USER);
        Annotations fromSet = Annotations.builder().setAudience(roles).build();
        roles.add(Role.ASSISTANT);
        assertEquals(Set.of(Role.USER), fromSet.audience().orElseThrow());
        assertEquals(Optional.of(Set.of()), Annotations.builder().setAudience(Set.of()).build().audience());
    }

    @ParameterizedTest
    @ValueSource(doubles = { -0.1, 1.1, Double.NaN })
    void priorityOutOfRange(double priority) {
        assertThrows(IllegalArgumentException.class, () -> Annotations.builder().setPriority(priority));
    }

    @Test
    void textResourceContents() {
        TextResourceContents plain = TextResourceContents.of("file:///a", "text");
        assertEquals("file:///a", plain.uri());
        assertEquals("text", plain.text());
        assertTrue(plain.mimeType().isEmpty());

        TextResourceContents full = TextResourceContents.builder("file:///a", "text")
                .setMimeType("text/plain")
                .putMetadata("k", "v")
                .build();
        assertEquals(Optional.of("text/plain"), full.mimeType());
        assertEquals(Map.of("k", "v"), full.metadata());
    }

    @Test
    void blobResourceContentsCopiesData() {
        byte[] data = DATA.clone();
        BlobResourceContents blob = BlobResourceContents.of("file:///a", data);
        data[0] = 9;
        assertArrayEquals(DATA, blob.blob());
        blob.blob()[0] = 9;
        assertArrayEquals(DATA, blob.blob());
        assertTrue(blob.mimeType().isEmpty());

        BlobResourceContents full = BlobResourceContents.builder("file:///a", DATA).setMimeType("image/png").build();
        assertEquals(Optional.of("image/png"), full.mimeType());
        assertEquals(BlobResourceContents.of("file:///a", DATA), blob);
    }

    @Test
    void resourceResponseFactories() {
        TextResourceContents text = assertInstanceOf(TextResourceContents.class,
                single(ResourceResponse.of("file:///a", "text")));
        assertEquals("text", text.text());
        assertTrue(text.mimeType().isEmpty());

        TextResourceContents typedText = assertInstanceOf(TextResourceContents.class,
                single(ResourceResponse.of("file:///a", "text", "text/plain")));
        assertEquals(Optional.of("text/plain"), typedText.mimeType());

        BlobResourceContents blob = assertInstanceOf(BlobResourceContents.class,
                single(ResourceResponse.of("file:///a", DATA)));
        assertArrayEquals(DATA, blob.blob());
        assertTrue(blob.mimeType().isEmpty());

        BlobResourceContents typedBlob = assertInstanceOf(BlobResourceContents.class,
                single(ResourceResponse.of("file:///a", DATA, "image/png")));
        assertEquals(Optional.of("image/png"), typedBlob.mimeType());
    }

    @Test
    void resourceResponseBuilder() {
        ResourceResponse response = ResourceResponse.builder()
                .addContents(TextResourceContents.of("a", "1"))
                .addContents(BlobResourceContents.of("b", DATA))
                .putMetadata("k", "v")
                .build();
        assertEquals(2, response.getContents().size());
        assertEquals(Map.of("k", "v"), response.metadata());
        assertThrows(UnsupportedOperationException.class,
                () -> response.getContents().add(TextResourceContents.of("c", "3")));
        assertTrue(ResourceResponse.builder().build().getContents().isEmpty());
    }

    @Test
    void promptResponse() {
        TextContent content = TextContent.of("hi");
        PromptResponse single = PromptResponse.of(Role.USER, content);
        assertTrue(single.description().isEmpty());
        PromptMessage message = single.messages().get(0);
        assertEquals(Role.USER, message.role());
        assertSame(content, message.content());

        PromptResponse full = PromptResponse.builder()
                .setDescription("desc")
                .addMessage(Role.USER, content)
                .addMessage(Role.ASSISTANT, TextContent.of("hello"))
                .putMetadata("k", "v")
                .build();
        assertEquals(Optional.of("desc"), full.description());
        assertEquals(List.of(Role.USER, Role.ASSISTANT), full.messages().stream().map(PromptMessage::role).toList());
        assertEquals(Map.of("k", "v"), full.metadata());
        assertThrows(UnsupportedOperationException.class, () -> full.messages().clear());
        assertThrows(NullPointerException.class, () -> PromptResponse.builder().addMessage(null, content));
        assertThrows(NullPointerException.class, () -> PromptResponse.builder().addMessage(Role.USER, null));
    }

    @Test
    void toolResponseFactories() {
        ToolResponse text = ToolResponse.ofText("ok");
        assertFalse(text.isError());
        assertEquals("ok", ((TextContent) text.content().get(0)).text());
        assertTrue(text.structuredContent().isEmpty());

        ToolResponse error = ToolResponse.ofError("boom");
        assertTrue(error.isError());
        assertEquals("boom", ((TextContent) error.content().get(0)).text());

        Object value = Map.of("a", 1);
        ToolResponse structured = ToolResponse.ofStructured(value);
        assertFalse(structured.isError());
        assertTrue(structured.content().isEmpty());
        assertSame(value, structured.structuredContent().orElseThrow());
    }

    @Test
    void toolResponseBuilder() {
        ToolResponse empty = ToolResponse.builder().build();
        assertTrue(empty.content().isEmpty());
        assertFalse(empty.isError());
        assertTrue(empty.structuredContent().isEmpty());
        assertTrue(empty.metadata().isEmpty());

        ToolResponse full = ToolResponse.builder()
                .addContent(ImageContent.of(DATA, "image/png"))
                .addTextContent("text")
                .setStructuredContent("s")
                .setError(true)
                .putMetadata("k", "v")
                .build();
        List<ContentBlock> content = full.content();
        assertInstanceOf(ImageContent.class, content.get(0));
        assertEquals("text", ((TextContent) content.get(1)).text());
        assertEquals(Optional.of("s"), full.structuredContent());
        assertTrue(full.isError());
        assertEquals(Map.of("k", "v"), full.metadata());
        assertThrows(UnsupportedOperationException.class, () -> content.add(TextContent.of("x")));
        assertTrue(ToolResponse.builder().setStructuredContent("x").setStructuredContent(null).build()
                .structuredContent().isEmpty());
    }

    @Test
    void completionFactories() {
        CompletionResult complete = CompletionResult.newCompleteResult(List.of("a", "b"));
        assertEquals(List.of("a", "b"), complete.values());
        assertEquals(OptionalInt.of(2), complete.total());
        assertEquals(Optional.of(false), complete.hasMore());

        CompletionResult incomplete = CompletionResult.newIncompleteResult(List.of("a"));
        assertEquals(OptionalInt.empty(), incomplete.total());
        assertEquals(Optional.of(true), incomplete.hasMore());

        CompletionResult more = CompletionResult.newResult(List.of("a"), 5);
        assertEquals(OptionalInt.of(5), more.total());
        assertEquals(Optional.of(true), more.hasMore());

        CompletionResult exact = CompletionResult.newResult(List.of("a"), 1);
        assertEquals(Optional.of(false), exact.hasMore());

        assertThrows(IllegalArgumentException.class, () -> CompletionResult.newResult(List.of("a", "b"), 1));
    }

    @Test
    void completionBuilder() {
        CompletionResult empty = CompletionResult.builder().build();
        assertTrue(empty.values().isEmpty());
        assertTrue(empty.total().isEmpty());
        assertTrue(empty.hasMore().isEmpty());

        List<String> values = new ArrayList<>(List.of("b", "c"));
        CompletionResult full = CompletionResult.builder()
                .addValue("a")
                .addValues(values)
                .setTotal(10)
                .setHasMore(true)
                .putMetadata("k", "v")
                .build();
        values.add("d");
        assertEquals(List.of("a", "b", "c"), full.values());
        assertEquals(OptionalInt.of(10), full.total());
        assertEquals(Optional.of(true), full.hasMore());
        assertEquals(Map.of("k", "v"), full.metadata());
        assertTrue(CompletionResult.builder().setHasMore(true).setHasMore(null).build().hasMore().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CompletionResult.builder().setTotal(-1));
        assertThrows(NullPointerException.class, () -> CompletionResult.builder().addValue(null));
    }

    @Test
    void icon() {
        Icon simple = Icon.of("https://example.com/i.png", "image/png");
        assertEquals("https://example.com/i.png", simple.src());
        assertEquals(Optional.of("image/png"), simple.mimeType());
        assertTrue(simple.sizes().isEmpty());
        assertTrue(simple.theme().isEmpty());

        Icon sized = Icon.builder("i.png").addSize(48, 48).addSize(96, 64).setTheme(Icon.Theme.DARK).build();
        assertEquals(List.of("48x48", "96x64"), sized.sizes());
        assertEquals(Optional.of(Icon.Theme.DARK), sized.theme());
        assertTrue(sized.mimeType().isEmpty());

        Icon any = Icon.builder("i.svg").setAnySize().setAnySize().build();
        assertEquals(List.of("any"), any.sizes());

        assertThrows(IllegalStateException.class, () -> Icon.builder("i").addSize(1, 1).setAnySize());
        assertThrows(IllegalStateException.class, () -> Icon.builder("i").setAnySize().addSize(1, 1));
        assertThrows(IllegalArgumentException.class, () -> Icon.builder("i").addSize(0, 1));
        assertThrows(UnsupportedOperationException.class, () -> sized.sizes().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", "a", "a1", "a-b_c.d", "com.example/key", "com.example/", "a-1.b2/x", "" })
    void validMetaKeys(String key) {
        assertEquals(Map.of(key, "v"), TextContent.builder("t").putMetadata(key, "v").build().metadata());
    }

    @ParameterizedTest
    @ValueSource(strings = { "-a", "a-", "a b", "1com/x", "com./x", "com-/x", "/x", "com.example//x", "a/b/c",
            "_x", "io.modelcontextprotocol/progress" })
    void invalidMetaKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> TextContent.builder("t").putMetadata(key, "v"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolResponse.builder().setMetadata(Map.of(key, "v")));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddedResource.builder("t", "u").putResourceMeta(key, "v"));
    }

    @Test
    void metadataIsCopiedAndUnmodifiable() {
        Map<String, Object> source = new HashMap<>(Map.of("a", 1));
        ToolResponse.Builder builder = ToolResponse.builder().putMetadata("x", 0).setMetadata(source);
        source.put("b", 2);
        ToolResponse response = builder.putMetadata("c", null).build();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1);
        expected.put("c", null);
        assertEquals(expected, response.metadata());
        assertThrows(UnsupportedOperationException.class, () -> response.metadata().put("d", 4));

        builder.putMetadata("e", 5);
        assertFalse(response.metadata().containsKey("e"));
        assertNotSame(builder.build(), response);
    }

    @Test
    void failedSetMetadataKeepsPreviousEntries() {
        TextContent.Builder builder = TextContent.builder("t").putMetadata("a", 1);
        assertThrows(IllegalArgumentException.class, () -> builder.setMetadata(Map.of("bad key", 2)));
        assertEquals(Map.of("a", 1), builder.build().metadata());
    }

    @Test
    void implementationInfo() {
        var info = ImplementationInfoImpl.of("srv", null, null, "", null, null);
        assertEquals("srv", info.name());
        assertEquals("srv", info.title());
        assertEquals("", info.version());
        assertTrue(info.description().isEmpty());
        assertTrue(info.websiteUrl().isEmpty());
        assertTrue(info.icons().isEmpty());

        Icon icon = Icon.of("i.png", "image/png");
        var full = ImplementationInfoImpl.of("srv", "Server", "1.0", "Does things", "https://example.com",
                List.of(icon));
        assertEquals("Server", full.title());
        assertEquals("1.0", full.version());
        assertEquals(Optional.of("Does things"), full.description());
        assertEquals(Optional.of("https://example.com"), full.websiteUrl());
        assertEquals(List.of(icon), full.icons());

        var empty = ImplementationInfoImpl.empty();
        assertEquals("", empty.name());
        assertEquals("", empty.title());
        assertEquals("", empty.version());
        assertTrue(empty.description().isEmpty());
        assertTrue(empty.icons().isEmpty());
    }

    private static ResourceContents single(ResourceResponse response) {
        assertEquals(1, response.getContents().size());
        return response.getContents().get(0);
    }
}
