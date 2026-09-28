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
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.spi.ImplementationInfoImpl;

/**
 * The MCP Servers of one application, keyed by the {@code McpApplication} subclass that declares each.
 */
public final class ServerRegistry {

    private final Map<Class<?>, McpServerModel> servers;
    private final CremaSettings settings;

    private ServerRegistry(Map<Class<?>, McpServerModel> servers, CremaSettings settings) {
        this.servers = servers;
        this.settings = settings;
    }

    /**
     * An {@code McpApplication} subclass and the MCP Server it declares.
     *
     * @param icons the MCP Server's icons, from {@code @Icons} on the subclass
     */
    public record Declaration(Class<?> application, ServerSettings settings, List<Icon> icons) {
    }

    /**
     * The registry and the deployment problems found while building it. The registry is only usable if there are
     * no problems. Warnings name questionable but valid declarations.
     */
    public record Result(ServerRegistry registry, List<String> problems, List<String> warnings) {
    }

    /**
     * Assigns Features to the MCP Servers they are bound to, and checks what can only be checked per MCP Server:
     * unique MCP Server names, bindings to declared MCP Servers, unique Feature names and resource URIs, and
     * completion references. Resource Templates of the same shape in one MCP Server are warnings.
     */
    public static Result build(List<Declaration> declarations, List<Feature> features, CremaSettings settings) {
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
            for (String server : feature.method().servers()) {
                if (byName.containsKey(server)) {
                    featuresByServer.computeIfAbsent(server, s -> new ArrayList<>()).add(feature);
                } else {
                    problems.add(where(feature) + ": is bound to the MCP Server '" + display(server)
                            + "', but no McpApplication declares it" + (McpServer.DEFAULT.equals(server)
                                    ? "; declare one with @ApplicationPath(\"mcp\") public class ... extends "
                                            + "McpApplication {}"
                                    : "; declare one with @McpServerInfo(name = \"" + server + "\")"));
                }
            }
        }
        Map<Class<?>, McpServerModel> servers = new LinkedHashMap<>();
        for (Declaration declaration : byName.values()) {
            String name = declaration.settings().name();
            List<Feature> bound = featuresByServer.getOrDefault(name, List.of());
            checkServer(name, bound, problems);
            checkTemplateShapes(name, bound, warnings);
            ServerSettings s = declaration.settings();
            servers.put(declaration.application(), new McpServerModel(declaration.application(), s,
                    ImplementationInfoImpl.of(s.wireName(), s.title(), s.version(), s.description(), s.websiteUrl(),
                            declaration.icons()),
                    settings.listTtlMs(), bound));
        }
        return new Result(new ServerRegistry(servers, settings), List.copyOf(problems), List.copyOf(warnings));
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

    private static void checkServer(String server, List<Feature> features, List<String> problems) {
        String in = " in MCP Server '" + display(server) + "'";
        unique(features, Feature.Tool.class, Feature::name, quoted("Tool name"), in, problems);
        unique(features, Feature.Resource.class, Feature::name, quoted("Resource name"), in, problems);
        unique(features, Feature.Resource.class, Feature.Resource::uri, quoted("resource URI"), in, problems);
        unique(features, Feature.ResourceTemplate.class, Feature::name, quoted("Resource Template name"), in,
                problems);
        unique(features, Feature.Prompt.class, Feature::name, quoted("Prompt name"), in, problems);
        unique(features, Feature.Completion.class, Feature::name, key -> "Completion Method for " + key, in,
                problems);
        for (Feature feature : features) {
            if (feature instanceof Feature.Completion completion) {
                checkCompletion(completion, features, in, problems);
            }
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

    private static void checkCompletion(Feature.Completion completion, List<Feature> features, String in,
            List<String> problems) {
        Optional<List<String>> arguments;
        String what;
        if (completion.kind() == FeatureType.PROMPT) {
            what = "Prompt";
            arguments = features.stream().filter(Feature.Prompt.class::isInstance)
                    .filter(f -> f.name().equals(completion.target())).findFirst()
                    .map(f -> f.method().arguments().stream().map(Param.Argument::name).toList());
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
        Map<String, F> seen = new HashMap<>();
        for (Feature feature : features) {
            if (type.isInstance(feature)) {
                F typed = type.cast(feature);
                F previous = seen.putIfAbsent(key.apply(typed), typed);
                if (previous != null) {
                    problems.add(where(feature) + ": duplicate " + what.apply(key.apply(typed)) + in
                            + ", also used by " + previous.method().describe());
                }
            }
        }
    }

    private static String where(Feature feature) {
        return "@" + annotation(feature) + " method " + feature.method().describe();
    }

    private static String annotation(Feature feature) {
        if (feature instanceof Feature.Completion completion) {
            return completion.kind() == FeatureType.PROMPT ? "CompletePrompt" : "CompleteResourceTemplate";
        }
        return feature.getClass().getSimpleName();
    }

    private static String display(String server) {
        return McpServer.DEFAULT.equals(server) ? "default" : server;
    }
}
