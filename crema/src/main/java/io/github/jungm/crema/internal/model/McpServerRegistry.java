package io.github.jungm.crema.internal.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.McpServer;

import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.McpServerSettings;
import io.github.jungm.crema.internal.spi.ImplementationInfoImpl;

/**
 * The MCP Servers of one application, keyed by the {@code McpApplication} subclass that declares each.
 */
public final class McpServerRegistry {

    private final Map<Class<?>, McpServerModel> servers;
    private final CremaSettings settings;

    private McpServerRegistry(Map<Class<?>, McpServerModel> servers, CremaSettings settings) {
        this.servers = servers;
        this.settings = settings;
    }

    /**
     * An {@code McpApplication} subclass and the MCP Server it declares.
     *
     * @param icons the MCP Server's icons, from {@code @Icons} on the subclass
     */
    public record Declaration(Class<?> application, McpServerSettings settings, List<Icon> icons) {
    }

    /**
     * The registry and the deployment problems found while building it. The registry is only usable if there are
     * no problems. Warnings name questionable but valid declarations.
     */
    public record Result(McpServerRegistry registry, List<String> problems, List<String> warnings) {
    }

    /**
     * Assigns Features and Completion Methods to the MCP Servers they are bound to, and checks what can only be
     * checked per MCP Server: unique MCP Server names, bindings to declared MCP Servers, unique Feature names and
     * resource URIs, and the Prompts and Resource Templates Completion Methods refer to. Resource Templates of the
     * same shape in one MCP Server are warnings.
     */
    public static Result build(List<Declaration> declarations, List<Feature> features, List<Completion> completions,
            CremaSettings settings) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Declaration> byName = new LinkedHashMap<>();
        for (Declaration declaration : declarations) {
            Declaration previous = byName.putIfAbsent(declaration.settings().name(), declaration);
            if (previous != null) {
                problems.add("McpApplication " + declaration.application().getName() + " declares the MCP Server '"
                        + display(declaration.settings().name()) + "', which McpApplication "
                        + previous.application().getName() + " declares already; set a different "
                        + "@McpServerInfo(name = ...)");
            }
        }
        Map<String, List<Feature>> featuresByServer = new HashMap<>();
        for (Feature feature : features) {
            bind(feature, feature.method(), where(feature), byName, featuresByServer, problems);
        }
        Map<String, List<Completion>> completionsByServer = new HashMap<>();
        for (Completion completion : completions) {
            bind(completion, completion.method(), where(completion), byName, completionsByServer, problems);
        }
        Map<Class<?>, McpServerModel> servers = new LinkedHashMap<>();
        for (Declaration declaration : byName.values()) {
            String name = declaration.settings().name();
            List<Feature> bound = featuresByServer.getOrDefault(name, List.of());
            List<Completion> boundCompletions = completionsByServer.getOrDefault(name, List.of());
            checkServer(name, bound, boundCompletions, problems);
            checkTemplateShapes(name, bound, warnings);
            McpServerSettings s = declaration.settings();
            servers.put(declaration.application(), new McpServerModel(declaration.application(),
                    ImplementationInfoImpl.of(s.wireName(), s.title(), s.version(), s.description(), s.websiteUrl(),
                            declaration.icons()),
                    s.instructions(), settings.listTtlMs(), bound, boundCompletions));
        }
        return new Result(new McpServerRegistry(servers, settings), List.copyOf(problems), List.copyOf(warnings));
    }

    /**
     * The MCP Server of an {@code McpApplication} subclass, also when a subclass of it (such as a proxy) is given.
     */
    public Optional<McpServerModel> server(Class<?> application) {
        for (Class<?> type = application; type != null; type = type.getSuperclass()) {
            McpServerModel server = servers.get(type);
            if (server != null) {
                return Optional.of(server);
            }
        }
        return Optional.empty();
    }

    public List<McpServerModel> servers() {
        return List.copyOf(servers.values());
    }

    public CremaSettings settings() {
        return settings;
    }

    private static <T> void bind(T item, ApplicationMethod method, String where, Map<String, Declaration> declared,
            Map<String, List<T>> byServer, List<String> problems) {
        for (String server : method.servers()) {
            if (declared.containsKey(server)) {
                byServer.computeIfAbsent(server, s -> new ArrayList<>()).add(item);
            } else {
                problems.add(where + ": is bound to the MCP Server '" + display(server)
                        + "', but no McpApplication declares it" + (McpServer.DEFAULT.equals(server)
                                ? "; declare one with @ApplicationPath(\"mcp\") public class ... extends "
                                        + "McpApplication {}"
                                : "; declare one with @McpServerInfo(name = \"" + server + "\")"));
            }
        }
    }

    private static void checkServer(String server, List<Feature> features, List<Completion> completions,
            List<String> problems) {
        String in = " in MCP Server '" + display(server) + "'";
        unique(features, Feature.Tool.class, Feature::name, quoted("Tool name"), in, problems);
        unique(features, Feature.Resource.class, Feature::name, quoted("Resource name"), in, problems);
        unique(features, Feature.Resource.class, Feature.Resource::uri, quoted("resource URI"), in, problems);
        unique(features, Feature.ResourceTemplate.class, Feature::name, quoted("Resource Template name"), in,
                problems);
        unique(features, Feature.Prompt.class, Feature::name, quoted("Prompt name"), in, problems);
        unique(completions, Completion::describe, Completion::method, McpServerRegistry::where,
                key -> "Completion Method for " + key, in, problems);
        for (Completion completion : completions) {
            checkCompletion(completion, features, in, problems);
        }
    }

    /**
     * Two templates of the same shape, such as {@code db:///{table}} and {@code db:///{name}}, match the same URIs,
     * so only one of them is ever invoked.
     */
    private static void checkTemplateShapes(String server, List<Feature> features, List<String> warnings) {
        Map<String, Feature.ResourceTemplate> seen = new HashMap<>();
        for (Feature feature : features) {
            if (feature instanceof Feature.ResourceTemplate template) {
                Feature.ResourceTemplate previous = seen.putIfAbsent(template.uriTemplate().shape(), template);
                if (previous != null) {
                    warnings.add(where(template) + ": the URI template '" + template.uriTemplate()
                            + "' matches the same URIs as '" + previous.uriTemplate() + "' of "
                            + previous.method().describe() + " in MCP Server '" + display(server)
                            + "', so only the Resource Template whose name sorts first is used");
                }
            }
        }
    }

    private static void checkCompletion(Completion completion, List<Feature> features, String in,
            List<String> problems) {
        Optional<List<String>> arguments;
        String what;
        if (completion.kind() == FeatureType.PROMPT) {
            what = "Prompt";
            arguments = features.stream().filter(Feature.Prompt.class::isInstance)
                    .filter(f -> f.name().equals(completion.target())).findFirst()
                    .map(f -> f.method().arguments().stream().map(Parameter.Argument::name).toList());
        } else {
            what = "Resource Template";
            arguments = features.stream().filter(Feature.ResourceTemplate.class::isInstance)
                    .map(Feature.ResourceTemplate.class::cast)
                    .filter(f -> f.name().equals(completion.target())).findFirst()
                    .map(f -> f.uriTemplate().variables());
        }
        if (arguments.isEmpty()) {
            problems.add(where(completion) + ": references the " + what + " '" + completion.target()
                    + "', which doesn't exist" + in);
        } else if (!arguments.get().contains(completion.argument())) {
            problems.add(where(completion) + ": completes the Argument '" + completion.argument() + "', but the "
                    + what + " '" + completion.target() + "' only has the Arguments " + arguments.get());
        }
    }

    private static Function<String, String> quoted(String what) {
        return key -> what + " '" + key + "'";
    }

    private static <F extends Feature> void unique(List<Feature> features, Class<F> type, Function<F, String> key,
            Function<String, String> what, String in, List<String> problems) {
        unique(features.stream().filter(type::isInstance).map(type::cast).toList(), key, Feature::method,
                McpServerRegistry::where, what, in, problems);
    }

    private static <T> void unique(List<T> items, Function<T, String> key, Function<T, ApplicationMethod> method,
            Function<T, String> where, Function<String, String> what, String in, List<String> problems) {
        Map<String, T> seen = new HashMap<>();
        for (T item : items) {
            T previous = seen.putIfAbsent(key.apply(item), item);
            if (previous != null) {
                problems.add(where.apply(item) + ": duplicate " + what.apply(key.apply(item)) + in
                        + ", also used by " + method.apply(previous).describe());
            }
        }
    }

    private static String where(Feature feature) {
        return "@" + feature.getClass().getSimpleName() + " method " + feature.method().describe();
    }

    private static String where(Completion completion) {
        return "@" + (completion.kind() == FeatureType.PROMPT ? "CompletePrompt" : "CompleteResourceTemplate")
                + " method " + completion.method().describe();
    }

    private static String display(String server) {
        return McpServer.DEFAULT.equals(server) ? "default" : server;
    }
}
