package io.github.jungm.crema.internal.json;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jungm.crema.internal.spi.IconImpl;
import io.github.jungm.crema.internal.spi.ImplementationInfoImpl;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.mcpjava.server.Icon;
import org.mcpjava.server.ImplementationInfo;
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
import org.mcpjava.server.tools.ToolResponse;

/**
 * Encodes the API's value types to their MCP {@code 2026-07-28} wire shapes with JSON-P, and parses
 * {@code clientInfo}.
 * <p>
 * Methods taking a {@code valueEncoder} use it for application values: {@code _meta} entries and
 * structured content. {@code null} values encode as JSON {@code null} and {@link JsonValue}s pass through
 * unchanged without reaching the encoder.
 */
public final class ProtocolJson {

    /** The maximum number of values in a completion, per the spec. */
    public static final int MAX_COMPLETION_VALUES = 100;

    private ProtocolJson() {
    }

    /**
     * Encodes a {@code ContentBlock}: {@code text}, {@code image}, {@code audio}, {@code resource} or
     * {@code resource_link}.
     */
    public static JsonObject contentBlock(ContentBlock content, Function<Object, JsonValue> valueEncoder) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder();
        Optional<Annotations> annotations;
        if (content instanceof TextContent text) {
            json.add("type", "text").add("text", text.text());
            annotations = text.annotations();
        } else if (content instanceof ImageContent image) {
            json.add("type", "image").add("data", base64(image.data())).add("mimeType", image.mimeType());
            annotations = image.annotations();
        } else if (content instanceof AudioContent audio) {
            json.add("type", "audio").add("data", base64(audio.data())).add("mimeType", audio.mimeType());
            annotations = audio.annotations();
        } else if (content instanceof EmbeddedResource resource) {
            json.add("type", "resource").add("resource", resourceContents(resource.resource(), valueEncoder));
            annotations = resource.annotations();
        } else if (content instanceof ResourceLink link) {
            json.add("type", "resource_link").add("uri", link.uri()).add("name", link.name());
            if (!link.title().equals(link.name())) {
                json.add("title", link.title());
            }
            link.description().ifPresent(description -> json.add("description", description));
            link.mimeType().ifPresent(mimeType -> json.add("mimeType", mimeType));
            link.size().ifPresent(size -> json.add("size", size));
            annotations = link.annotations();
        } else {
            throw new IllegalArgumentException("Unknown content block: " + content);
        }
        annotations.ifPresent(a -> json.add("annotations", annotations(a)));
        addMeta(json, content.metadata(), valueEncoder);
        return json.build();
    }

    /**
     * Encodes {@code Annotations}: {@code audience} as lowercase roles, {@code priority}, and
     * {@code lastModified} as an ISO-8601 instant.
     */
    public static JsonObject annotations(Annotations annotations) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder();
        annotations.audience().ifPresent(audience -> {
            JsonArrayBuilder roles = Json.FACTORY.createArrayBuilder();
            audience.forEach(role -> roles.add(role.name().toLowerCase(Locale.ROOT)));
            json.add("audience", roles);
        });
        annotations.priority().ifPresent(priority -> json.add("priority", priority));
        annotations.lastModified().ifPresent(lastModified -> json.add("lastModified", lastModified.toString()));
        return json.build();
    }

    /**
     * Encodes a {@code CallToolResult} without {@code resultType}: {@code content}, {@code structuredContent}
     * when present, {@code isError}, and the response's own {@code _meta} when non-empty. When the response has
     * structured content but no content blocks, {@code content} holds one text block with the serialized
     * structured content, as the spec recommends for backwards compatibility.
     */
    public static JsonObject toolResult(ToolResponse response, Function<Object, JsonValue> valueEncoder) {
        JsonArrayBuilder content = Json.FACTORY.createArrayBuilder();
        response.content().forEach(block -> content.add(contentBlock(block, valueEncoder)));
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder();
        Optional<JsonValue> structured = response.structuredContent().map(value -> encode(value, valueEncoder));
        if (response.content().isEmpty() && structured.isPresent()) {
            content.add(Json.FACTORY.createObjectBuilder().add("type", "text").add("text", structured.get().toString()));
        }
        json.add("content", content);
        structured.ifPresent(value -> json.add("structuredContent", value));
        json.add("isError", response.isError());
        addMeta(json, response.metadata(), valueEncoder);
        return json.build();
    }

    /**
     * Encodes the {@code contents} array of a {@code ReadResourceResult}. The response's own {@code _meta}
     * is not part of it; see {@link #meta(Map, Function)}.
     */
    public static JsonArray resourceContents(ResourceResponse response, Function<Object, JsonValue> valueEncoder) {
        JsonArrayBuilder json = Json.FACTORY.createArrayBuilder();
        response.getContents().forEach(contents -> json.add(resourceContents(contents, valueEncoder)));
        return json.build();
    }

    /**
     * Encodes a {@code TextResourceContents} or {@code BlobResourceContents} (Base64 {@code blob}).
     */
    public static JsonObject resourceContents(ResourceContents contents, Function<Object, JsonValue> valueEncoder) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder().add("uri", contents.uri());
        contents.mimeType().ifPresent(mimeType -> json.add("mimeType", mimeType));
        if (contents instanceof TextResourceContents text) {
            json.add("text", text.text());
        } else if (contents instanceof BlobResourceContents blob) {
            json.add("blob", base64(blob.blob()));
        } else {
            throw new IllegalArgumentException("Unknown resource contents: " + contents);
        }
        addMeta(json, contents.metadata(), valueEncoder);
        return json.build();
    }

    /**
     * Encodes a {@code GetPromptResult} without {@code resultType}: {@code description} when present,
     * {@code messages}, and the response's own {@code _meta} when non-empty.
     */
    public static JsonObject promptResult(PromptResponse response, Function<Object, JsonValue> valueEncoder) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder();
        response.description().ifPresent(description -> json.add("description", description));
        JsonArrayBuilder messages = Json.FACTORY.createArrayBuilder();
        response.messages().forEach(message -> messages.add(promptMessage(message, valueEncoder)));
        json.add("messages", messages);
        addMeta(json, response.metadata(), valueEncoder);
        return json.build();
    }

    /**
     * Encodes a {@code PromptMessage}: {@code role} (lowercase) and {@code content}.
     */
    public static JsonObject promptMessage(PromptMessage message, Function<Object, JsonValue> valueEncoder) {
        return Json.FACTORY.createObjectBuilder()
                .add("role", message.role().name().toLowerCase(Locale.ROOT))
                .add("content", contentBlock(message.content(), valueEncoder))
                .build();
    }

    /**
     * Encodes the inner {@code completion} object of a {@code CompleteResult}: {@code values},
     * {@code total} and {@code hasMore} when known. More than {@value #MAX_COMPLETION_VALUES} values are
     * truncated, and {@code hasMore} is then {@code true}. The result's own {@code _meta} is not part of it;
     * see {@link #meta(Map, Function)}.
     */
    public static JsonObject completion(CompletionResult result) {
        List<String> values = result.values();
        boolean truncated = values.size() > MAX_COMPLETION_VALUES;
        JsonArrayBuilder array = Json.FACTORY.createArrayBuilder();
        (truncated ? values.subList(0, MAX_COMPLETION_VALUES) : values).forEach(array::add);
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder().add("values", array);
        result.total().ifPresent(total -> json.add("total", total));
        if (truncated) {
            json.add("hasMore", true);
        } else {
            result.hasMore().ifPresent(hasMore -> json.add("hasMore", hasMore));
        }
        return json.build();
    }

    /**
     * Encodes an {@code Icon}: {@code src}, and {@code mimeType}, {@code sizes} and {@code theme} (lowercase)
     * when present.
     */
    public static JsonObject icon(Icon icon) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder().add("src", icon.src());
        icon.mimeType().ifPresent(mimeType -> json.add("mimeType", mimeType));
        if (!icon.sizes().isEmpty()) {
            JsonArrayBuilder sizes = Json.FACTORY.createArrayBuilder();
            icon.sizes().forEach(sizes::add);
            json.add("sizes", sizes);
        }
        icon.theme().ifPresent(theme -> json.add("theme", theme.name().toLowerCase(Locale.ROOT)));
        return json.build();
    }

    /**
     * Encodes an array of icons.
     */
    public static JsonArray icons(List<Icon> icons) {
        JsonArrayBuilder json = Json.FACTORY.createArrayBuilder();
        icons.forEach(icon -> json.add(icon(icon)));
        return json.build();
    }

    /**
     * Encodes an {@code Implementation}: {@code name} and {@code version} always; {@code title} when it is
     * non-empty and differs from the name; {@code description}, {@code websiteUrl} and {@code icons} when
     * present.
     */
    public static JsonObject implementation(ImplementationInfo info) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder().add("name", info.name());
        if (info.title() != null && !info.title().isEmpty() && !info.title().equals(info.name())) {
            json.add("title", info.title());
        }
        json.add("version", info.version());
        info.description().ifPresent(description -> json.add("description", description));
        info.websiteUrl().ifPresent(websiteUrl -> json.add("websiteUrl", websiteUrl));
        if (!info.icons().isEmpty()) {
            json.add("icons", icons(info.icons()));
        }
        return json.build();
    }

    /**
     * Parses an {@code Implementation} such as a request's {@code clientInfo}. Missing or mistyped fields
     * are treated as absent: name and version default to {@code ""}, the title to the name, and icons
     * without a string {@code src} are skipped. A {@code null} object yields
     * {@link ImplementationInfoImpl#empty()}.
     */
    public static ImplementationInfo implementation(JsonObject json) {
        if (json == null) {
            return ImplementationInfoImpl.empty();
        }
        String name = Json.string(json, "name").orElse("");
        List<Icon> icons = new ArrayList<>();
        JsonValue iconArray = json.get("icons");
        if (iconArray instanceof JsonArray array) {
            for (JsonValue value : array) {
                if (value instanceof JsonObject icon) {
                    parseIcon(icon).ifPresent(icons::add);
                }
            }
        }
        return ImplementationInfoImpl.of(name, Json.string(json, "title").orElse(null), Json.string(json, "version").orElse(""),
                Json.string(json, "description").orElse(null), Json.string(json, "websiteUrl").orElse(null), icons);
    }

    /**
     * Encodes a {@code _meta} object.
     */
    public static JsonObject meta(Map<String, Object> metadata, Function<Object, JsonValue> valueEncoder) {
        JsonObjectBuilder json = Json.FACTORY.createObjectBuilder();
        metadata.forEach((key, value) -> json.add(key, encode(value, valueEncoder)));
        return json.build();
    }

    private static Optional<Icon> parseIcon(JsonObject json) {
        Optional<String> src = Json.string(json, "src");
        if (src.isEmpty()) {
            return Optional.empty();
        }
        List<String> sizes = new ArrayList<>();
        if (json.get("sizes") instanceof JsonArray array) {
            array.forEach(size -> {
                if (size instanceof JsonString string) {
                    sizes.add(string.getString());
                }
            });
        }
        Optional<Icon.Theme> theme = Json.string(json, "theme").flatMap(value -> {
            for (Icon.Theme candidate : Icon.Theme.values()) {
                if (candidate.name().equalsIgnoreCase(value)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        });
        return Optional.of(new IconImpl(src.get(), Json.string(json, "mimeType"), sizes, theme));
    }

    private static void addMeta(JsonObjectBuilder json, Map<String, Object> metadata,
            Function<Object, JsonValue> valueEncoder) {
        if (!metadata.isEmpty()) {
            json.add("_meta", meta(metadata, valueEncoder));
        }
    }

    private static JsonValue encode(Object value, Function<Object, JsonValue> valueEncoder) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof JsonValue json) {
            return json;
        }
        JsonValue encoded = valueEncoder.apply(value);
        return encoded == null ? JsonValue.NULL : encoded;
    }

    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
