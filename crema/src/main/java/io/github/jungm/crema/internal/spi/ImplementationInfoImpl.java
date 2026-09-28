package io.github.jungm.crema.internal.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.Icon;
import org.mcpjava.server.ImplementationInfo;

/**
 * Immutable {@link ImplementationInfo}, describing an MCP Server or an MCP Client.
 */
public record ImplementationInfoImpl(String name, String title, String version, Optional<String> description,
        Optional<String> websiteUrl, List<Icon> icons) implements ImplementationInfo {

    private static final ImplementationInfo EMPTY = new ImplementationInfoImpl("", "", "", Optional.empty(),
            Optional.empty(), List.of());

    public ImplementationInfoImpl {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(websiteUrl, "websiteUrl");
        icons = List.copyOf(icons);
    }

    /**
     * Creates implementation info. A {@code null} or empty {@code title} falls back to {@code name};
     * {@code null} or empty {@code description} and {@code websiteUrl} are absent; a {@code null}
     * {@code version} is empty.
     */
    public static ImplementationInfo of(String name, String title, String version, String description,
            String websiteUrl, List<Icon> icons) {
        return new ImplementationInfoImpl(name, isEmpty(title) ? name : title, version == null ? "" : version,
                optional(description), optional(websiteUrl), icons == null ? List.of() : icons);
    }

    /**
     * Implementation info whose name, title and version are empty strings, standing in for an MCP
     * Client that sent none.
     */
    public static ImplementationInfo empty() {
        return EMPTY;
    }

    private static Optional<String> optional(String value) {
        return isEmpty(value) ? Optional.empty() : Optional.of(value);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
