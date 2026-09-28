package io.github.jungm.crema.internal.cdi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;

import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.McpServerSettings;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.McpServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Services;
import io.github.jungm.crema.internal.security.CremaAccessPolicy;
import io.github.jungm.crema.internal.security.Protection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Brings together the two halves of an application's MCP Servers, which the Runtime discovers independently: the
 * Features, found by the CDI extension, and the {@code McpApplication} subclasses, found by a
 * {@code ServletContainerInitializer}. Whichever half arrives second builds the {@link McpTransport} and reports the
 * deployment problems that need both halves.
 * <p>
 * Crema belongs in each web application's {@code WEB-INF/lib}, so this state belongs to one web application.
 * Deployment fails for a second web application that brings Features or {@code McpApplication}s to the same Crema
 * classes, and for a web application with an {@code McpApplication} whose Features CDI never delivers.
 */
public final class CremaDeployment {

    private static final Logger LOG = Logger.getLogger(CremaDeployment.class.getName());

    static final String SHARED = "Crema's classes are shared by more than one web application; package Crema in "
            + "the WEB-INF/lib of each web application that uses it";
    static final String CDI_INACTIVE = "The web application declares an McpApplication, but CDI didn't discover "
            + "its Features; enable CDI for the web application (for example with a beans.xml)";

    private static Catalog catalog;
    private static Declarations declarations;
    private static ServletContext owner;
    private static volatile McpTransport transport;
    private static CremaAccessPolicy access;

    private CremaDeployment() {
    }

    /**
     * The Features the CDI extension found, and what handling requests needs.
     */
    public record Catalog(List<Feature> features, Mapping mapping, ContentEncoders encoders, IconLookup icons) {
    }

    /**
     * The MCP Servers the {@code McpApplication} subclasses declare, and the application-wide settings.
     */
    public record Declarations(List<Declaration> declarations, CremaSettings settings) {
    }

    /**
     * An {@code McpApplication} subclass and the MCP Server it declares, as the {@code ServletContainerInitializer}
     * finds it. It becomes a {@link McpServerRegistry.Declaration}, with its icons resolved, once the Features and
     * with them the {@link IconLookup} are known.
     *
     * @param iconProvider the provider of {@code @Icons} on the subclass, or {@code null}
     * @param protection the protection of its MCP Server, or {@code null} if it isn't protected
     */
    public record Declaration(Class<?> application, McpServerSettings settings,
            Class<? extends IconProvider> iconProvider, Protection protection) {

        public Declaration(Class<?> application, McpServerSettings settings,
                Class<? extends IconProvider> iconProvider) {
            this(application, settings, iconProvider, null);
        }
    }

    /**
     * Called by the CDI extension after deployment validation. A second call means that another web application
     * shares Crema's classes; it is a problem if that application has Features.
     */
    public static synchronized void featuresDiscovered(Catalog discovered, Consumer<String> problems) {
        if (catalog != null) {
            if (!discovered.features().isEmpty()) {
                problems.accept(SHARED);
            }
            return;
        }
        catalog = discovered;
        if (declarations != null) {
            build().forEach(problems);
        }
    }

    /**
     * Called once the {@code McpApplication} subclasses of the web application with {@code context} are known.
     * If there are any, the web application becomes the one this state belongs to, and deployment fails when it
     * has started without CDI having delivered its Features.
     *
     * @return the deployment problems found, if the Features are already known
     */
    public static synchronized List<String> applicationsDiscovered(ServletContext context, Declarations discovered) {
        if (owner != null && !sameApplication(owner, context)) {
            return discovered.declarations().isEmpty() ? List.of() : List.of(SHARED);
        }
        if (!discovered.declarations().isEmpty()) {
            owner = context;
            context.addListener(new CdiCheck());
        }
        declarations = discovered;
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
        stop();
        catalog = null;
        declarations = null;
        owner = null;
    }

    /**
     * Stops serving MCP for good, when the web application stops: releases what the MCP Servers hold and Crema's
     * {@code Jsonb}.
     */
    static synchronized void stop() {
        shutdown();
        if (catalog != null) {
            try {
                catalog.mapping().close();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Couldn't close Crema's Jsonb", e);
            }
        }
    }

    /**
     * Stops serving MCP and releases what the MCP Servers hold, such as background key retrieval.
     */
    static synchronized void shutdown() {
        transport = null;
        if (access != null) {
            access.close();
            access = null;
        }
    }

    /**
     * The problem to report once the web application has started, if CDI never delivered its Features.
     */
    static synchronized Optional<String> cdiProblem() {
        return catalog == null && declarations != null && !declarations.declarations().isEmpty()
                ? Optional.of(CDI_INACTIVE) : Optional.empty();
    }

    private static boolean sameApplication(ServletContext a, ServletContext b) {
        return a == b || a.getClassLoader() == b.getClassLoader();
    }

    /**
     * Fails the start of the web application if CDI never delivered its Features. Listeners support injection, so
     * CDI has started by the time they are initialized. Once the web application stops, it releases what the MCP
     * Servers hold.
     */
    static final class CdiCheck implements ServletContextListener {

        @Override
        public void contextInitialized(ServletContextEvent event) {
            Optional<String> problem = cdiProblem();
            if (problem.isPresent()) {
                LOG.log(Level.SEVERE, problem.get());
                throw new IllegalStateException(problem.get());
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            stop();
        }
    }

    private static List<String> build() {
        List<String> problems = new ArrayList<>();
        List<McpServerRegistry.Declaration> resolved = new ArrayList<>();
        for (Declaration declaration : declarations.declarations()) {
            List<Icon> icons = List.of();
            if (declaration.iconProvider() != null) {
                try {
                    icons = catalog.icons().icons(declaration.iconProvider(), null,
                            declaration.settings().wireName());
                } catch (RuntimeException e) {
                    problems.add("McpApplication " + declaration.application().getName() + ": " + e.getMessage());
                }
            }
            resolved.add(new McpServerRegistry.Declaration(declaration.application(), declaration.settings(), icons));
        }
        McpServerRegistry.Result result = McpServerRegistry.build(resolved, catalog.features(),
                declarations.settings());
        problems.addAll(result.problems());
        result.warnings().forEach(LOG::warning);
        if (!problems.isEmpty()) {
            return problems;
        }
        Map<Class<?>, Protection> protections = new HashMap<>();
        for (Declaration declaration : declarations.declarations()) {
            if (declaration.protection() != null) {
                protections.put(declaration.application(), declaration.protection());
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
        transport = new McpTransport(result.registry(),
                new Dispatcher(new Services(catalog.mapping(), catalog.encoders(), access)));
        return problems;
    }
}
