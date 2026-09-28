package io.github.jungm.crema.internal.cdi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

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

/**
 * Brings together the two halves of an application's MCP Servers, which the Runtime discovers independently: the
 * Features, found by the CDI extension, and the {@code McpApplication} subclasses, found by a
 * {@code ServletContainerInitializer}. Whichever half arrives second builds the {@link McpTransport} and reports the
 * deployment problems that need both halves.
 * <p>
 * Crema is packaged in {@code WEB-INF/lib}, so this state belongs to one web application.
 */
public final class CremaDeployment {

    private static final Logger LOG = Logger.getLogger(CremaDeployment.class.getName());

    private static Catalog catalog;
    private static Applications applications;
    private static volatile McpTransport transport;

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
     */
    public record Application(Class<?> type, ServerSettings settings, Class<? extends IconProvider> iconProvider) {
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
        catalog = null;
        applications = null;
        transport = null;
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
        result.warnings().forEach(LOG::warning);
        if (problems.isEmpty()) {
            transport = new McpTransport(result.registry(), new Dispatcher(catalog.services()));
        }
        return problems;
    }
}
