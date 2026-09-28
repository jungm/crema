package io.github.jungm.crema.internal.config;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.mcpjava.server.McpServer;

import io.github.jungm.crema.McpServerInfo;

/**
 * The resolved description of one MCP Server: configuration values override {@link McpServerInfo} attributes;
 * absent values are {@code null}, except {@link #version()}, which falls back to the web application's
 * manifest version and then to {@code 0.0.0}.
 */
public record ServerSettings(String name, String title, String version, String description, String instructions,
        String websiteUrl, String resource) {

    private static final String FALLBACK_VERSION = "0.0.0";

    /**
     * Resolves the settings of the MCP Server that {@code info} declares ({@code null} for the default MCP
     * Server without annotation).
     */
    public static ServerSettings resolve(McpServerInfo info, ConfigLookup config,
            Supplier<Optional<String>> manifestVersion) {
        String name = info == null ? McpServer.DEFAULT : info.name();
        String prefix = keyPrefix(name);
        Function<String, Optional<String>> lookup = attribute -> config.get(prefix + attribute);
        return new ServerSettings(name,
                value(lookup.apply("title"), info == null ? "" : info.title()),
                value(lookup.apply("version"), info == null ? "" : info.version(), manifestVersion)
                        .orElse(FALLBACK_VERSION),
                value(lookup.apply("description"), info == null ? "" : info.description()),
                value(lookup.apply("instructions"), info == null ? "" : info.instructions()),
                value(lookup.apply("website-url"), info == null ? "" : info.websiteUrl()),
                lookup.apply("resource").orElse(null));
    }

    /**
     * The configuration key prefix of an MCP Server: {@code crema.default-server.} or
     * {@code crema.servers.<name>.}.
     */
    public static String keyPrefix(String serverName) {
        return McpServer.DEFAULT.equals(serverName) ? "crema.default-server." : "crema.servers." + serverName + ".";
    }

    /**
     * The name sent to MCP Clients: {@code default} for the default MCP Server, else its name.
     */
    public String wireName() {
        return McpServer.DEFAULT.equals(name) ? "default" : name;
    }

    private static String value(Optional<String> configured, String annotated) {
        return configured.orElse(annotated.isEmpty() ? null : annotated);
    }

    private static Optional<String> value(Optional<String> configured, String annotated,
            Supplier<Optional<String>> fallback) {
        return Optional.ofNullable(value(configured, annotated)).or(fallback);
    }
}
