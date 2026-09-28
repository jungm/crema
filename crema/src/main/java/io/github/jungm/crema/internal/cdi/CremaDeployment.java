package io.github.jungm.crema.internal.cdi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;

import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.ServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Services;
import io.github.jungm.crema.internal.security.CremaAccessPolicy;
import io.github.jungm.crema.internal.security.Protection;

/**
 * Brings together the two halves of an application's MCP Servers, which the Runtime discovers independently: the
 * Features, found by the CDI extension, and the {@code McpApplication} subclasses, found by a
 * {@code ServletContainerInitializer}. Whichever half arrives second builds the {@link McpTransport} and reports the
 * deployment problems that need both halves.
 * <p>
 * Crema is packaged in {@code WEB-INF/lib}, so this state belongs to one web application.
 */
public final class CremaDeployment {

    private static Catalog catalog;
    private static Applications applications;
    private static volatile McpTransport transport;
    private static CremaAccessPolicy access;

    private CremaDeployment() {
    }

    /**
     * The Features the CDI extension found, and what handling requests needs.
     */
    public record Catalog(List<Feature> features, Services services, IconLookup icons) {
    }

    /**
     * The {@code McpApplication} subclasses and the application-wide settings.
     */
    public record Applications(List<Application> applications, CremaSettings settings) {
    }

    /**
     * An {@code McpApplication} subclass.
     *
     * @param iconProvider the provider of {@code @Icons} on the subclass, or {@code null}
     * @param protection the protection of its MCP Server, or {@code null} if it isn't protected
     */
    public record Application(Class<?> type, ServerSettings settings, Class<? extends IconProvider> iconProvider,
            Protection protection) {

        public Application(Class<?> type, ServerSettings settings, Class<? extends IconProvider> iconProvider) {
            this(type, settings, iconProvider, null);
        }
    }

    /**
     * Called by the CDI extension after deployment validation.
     */
    public static synchronized void featuresDiscovered(Catalog discovered, Consumer<String> problems) {
        catalog = discovered;
        if (applications != null) {
            build().forEach(problems);
        }
    }

    /**
     * Called once the {@code McpApplication} subclasses are known.
     *
     * @return the deployment problems found, if the Features are already known
     */
    public static synchronized List<String> applicationsDiscovered(Applications discovered) {
        applications = discovered;
        return catalog == null ? List.of() : build();
    }

    /**
     * The transport of this application, once both halves are known and valid.
     */
    public static Optional<McpTransport> transport() {
        return Optional.ofNullable(transport);
    }

    /**
     * Forgets everything, for tests.
     */
    static synchronized void reset() {
        shutdown();
        catalog = null;
        applications = null;
    }

    /**
     * Stops serving MCP and releases what the MCP Servers hold, such as background key retrieval.
     */
    public static synchronized void shutdown() {
        transport = null;
        if (access != null) {
            access.close();
            access = null;
        }
    }

    private static List<String> build() {
        List<String> problems = new ArrayList<>();
        List<ServerRegistry.Declaration> declarations = new ArrayList<>();
        for (Application application : applications.applications()) {
            List<Icon> icons = List.of();
            if (application.iconProvider() != null) {
                try {
                    icons = catalog.icons().icons(application.iconProvider(), null,
                            application.settings().wireName());
                } catch (RuntimeException e) {
                    problems.add("McpApplication " + application.type().getName() + ": " + e.getMessage());
                }
            }
            declarations.add(new ServerRegistry.Declaration(application.type(), application.settings(), icons));
        }
        ServerRegistry.Result result = ServerRegistry.build(declarations, catalog.features(),
                applications.settings());
        problems.addAll(result.problems());
        if (!problems.isEmpty()) {
            return problems;
        }
        Map<Class<?>, Protection> protections = new HashMap<>();
        for (Application application : applications.applications()) {
            if (application.protection() != null) {
                protections.put(application.type(), application.protection());
            }
        }
        CremaAccessPolicy.Result policy = CremaAccessPolicy.create(result.registry().servers(), protections);
        problems.addAll(policy.problems());
        if (!problems.isEmpty()) {
            policy.policy().close();
            return problems;
        }
        shutdown();
        access = policy.policy();
        Services services = catalog.services();
        transport = new McpTransport(result.registry(),
                new Dispatcher(new Services(services.mapping(), services.encoders(), access)));
        return problems;
    }
}
