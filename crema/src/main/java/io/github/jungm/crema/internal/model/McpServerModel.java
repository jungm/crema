package io.github.jungm.crema.internal.model;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.mcpjava.server.FeatureType;
import org.mcpjava.server.ImplementationInfo;

import io.github.jungm.crema.internal.config.ServerSettings;

/**
 * One MCP Server: its description and its Features. Listed Features are sorted by name.
 */
public final class McpServerModel {

    private final Class<?> application;
    private final ServerSettings settings;
    private final ImplementationInfo info;
    private final long listTtlMs;
    private final Map<String, Feature.Tool> tools;
    private final Map<String, Feature.Resource> resources;
    private final Map<String, Feature.Resource> resourcesByUri;
    private final Map<String, Feature.ResourceTemplate> templates;
    private final Map<String, Feature.Prompt> prompts;
    private final Map<String, Feature.Completion> completions;

    /**
     * @param features the Features and completions bound to this MCP Server; names and URIs must be unique
     */
    public McpServerModel(Class<?> application, ServerSettings settings, ImplementationInfo info, long listTtlMs,
            Collection<? extends Feature> features) {
        this.application = application;
        this.settings = settings;
        this.info = info;
        this.listTtlMs = listTtlMs;
        this.tools = index(features, Feature.Tool.class, Feature::name);
        this.resources = index(features, Feature.Resource.class, Feature::name);
        this.resourcesByUri = index(features, Feature.Resource.class, Feature.Resource::uri);
        this.templates = index(features, Feature.ResourceTemplate.class, Feature::name);
        this.prompts = index(features, Feature.Prompt.class, Feature::name);
        this.completions = index(features, Feature.Completion.class,
                c -> completionKey(c.kind(), c.target(), c.argument()));
    }

    /**
     * The {@code McpApplication} subclass that declares this MCP Server.
     */
    public Class<?> application() {
        return application;
    }

    public ServerSettings settings() {
        return settings;
    }

    /**
     * The {@code serverInfo} sent to MCP Clients.
     */
    public ImplementationInfo info() {
        return info;
    }

    public long listTtlMs() {
        return listTtlMs;
    }

    public Collection<Feature.Tool> tools() {
        return tools.values();
    }

    public Optional<Feature.Tool> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Feature.Resource> resources() {
        return resources.values();
    }

    public Optional<Feature.Resource> resource(String uri) {
        return Optional.ofNullable(resourcesByUri.get(uri));
    }

    public Collection<Feature.ResourceTemplate> resourceTemplates() {
        return templates.values();
    }

    public Optional<Feature.ResourceTemplate> resourceTemplate(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    public Collection<Feature.Prompt> prompts() {
        return prompts.values();
    }

    public Optional<Feature.Prompt> prompt(String name) {
        return Optional.ofNullable(prompts.get(name));
    }

    public Optional<Feature.Completion> completion(FeatureType kind, String target, String argument) {
        return Optional.ofNullable(completions.get(completionKey(kind, target, argument)));
    }

    /**
     * All Features and completions.
     */
    public List<Feature> features() {
        return java.util.stream.Stream.of(tools.values(), resources.values(), templates.values(), prompts.values(),
                completions.values()).flatMap(Collection::stream).map(Feature.class::cast).toList();
    }

    private static String completionKey(FeatureType kind, String target, String argument) {
        return kind + "\u0000" + target + "\u0000" + argument;
    }

    private static <F extends Feature> Map<String, F> index(Collection<? extends Feature> features, Class<F> type,
            Function<F, String> key) {
        return Collections.unmodifiableMap(features.stream().filter(type::isInstance).map(type::cast)
                .sorted(Comparator.comparing(Feature::name))
                .collect(Collectors.toMap(key, f -> f, (a, b) -> a, TreeMap::new)));
    }
}
