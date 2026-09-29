package io.github.jungm.crema.internal.cdi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;
import org.mcpjava.server.McpServer;

import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.McpServerSettings;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.Completion;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.McpServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Services;
import io.github.jungm.crema.internal.security.Authenticator;
import io.github.jungm.crema.internal.security.AuthenticatorSetting;
import io.github.jungm.crema.internal.security.BasicMechanism;
import io.github.jungm.crema.internal.security.BeanMechanism;
import io.github.jungm.crema.internal.security.CremaAccessPolicy;
import io.github.jungm.crema.internal.security.Mechanism;
import io.github.jungm.crema.internal.security.OAuthMechanism;
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
     * The Features, Completion Methods and authenticators the CDI extension found, and what handling requests
     * needs.
     */
    public record Catalog(List<Feature> features, List<Completion> completions, Mapping mapping,
            ContentEncoders encoders, IconLookup icons, List<Authenticator> authenticators) {

        boolean isEmpty() {
            return features.isEmpty() && completions.isEmpty() && authenticators.isEmpty();
        }
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
     * @param authenticator how its MCP Server authenticates callers
     */
    public record Declaration(Class<?> application, McpServerSettings settings,
            Class<? extends IconProvider> iconProvider, AuthenticatorSetting authenticator) {

        public Declaration(Class<?> application, McpServerSettings settings,
                Class<? extends IconProvider> iconProvider) {
            this(application, settings, iconProvider, new AuthenticatorSetting.Unset());
        }
    }

    /**
     * Called by the CDI extension after deployment validation. A second call means that another web application
     * shares Crema's classes; it is a problem if that application has Features or Completion Methods.
     */
    public static synchronized void featuresDiscovered(Catalog discovered, Consumer<String> problems) {
        if (catalog != null) {
            if (!discovered.isEmpty()) {
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
        McpServerRegistry.Result result = McpServerRegistry.build(resolved, catalog.features(), catalog.completions(),
                declarations.settings());
        problems.addAll(result.problems());
        result.warnings().forEach(LOG::warning);
        if (!problems.isEmpty()) {
            return problems;
        }
        Map<Class<?>, Authenticator> authenticators = bindAuthenticators(problems);
        Map<Class<?>, Supplier<Mechanism>> chosen = chooseMechanisms(authenticators, problems);
        if (!problems.isEmpty()) {
            return problems;
        }
        Map<Class<?>, Mechanism> mechanisms = new HashMap<>();
        chosen.forEach((application, mechanism) -> mechanisms.put(application, mechanism.get()));
        CremaAccessPolicy.Result policy = CremaAccessPolicy.create(result.registry().servers(), mechanisms);
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

    /**
     * Binds each authenticator to the MCP Servers its {@code @McpServer} names, which must be declared and have no
     * other authenticator.
     *
     * @return the authenticator of each MCP Server that has one, by {@code McpApplication} subclass
     */
    private static Map<Class<?>, Authenticator> bindAuthenticators(List<String> problems) {
        Map<String, Declaration> byName = new HashMap<>();
        for (Declaration declaration : declarations.declarations()) {
            byName.put(declaration.settings().name(), declaration);
        }
        Map<Class<?>, Authenticator> bound = new HashMap<>();
        for (Authenticator authenticator : catalog.authenticators()) {
            for (String server : authenticator.servers()) {
                Declaration declaration = byName.get(server);
                if (declaration == null) {
                    problems.add(authenticator.describe() + ": is bound to the MCP Server '" + display(server)
                            + "', but no McpApplication declares it");
                    continue;
                }
                Authenticator previous = bound.putIfAbsent(declaration.application(), authenticator);
                if (previous != null) {
                    problems.add(authenticator.describe() + ": is bound to the MCP Server '"
                            + declaration.settings().wireName() + "', which " + previous.describe()
                            + " authenticates already; an MCP Server has at most one McpAuthenticator");
                }
            }
        }
        return bound;
    }

    /**
     * Chooses how each MCP Server authenticates its callers, as its {@code authenticator} setting says, and checks
     * that the {@code McpAuthenticator} beans agree: {@code bean} needs one, {@code oauth} and {@code basic} allow
     * none, and without the setting an open MCP Server uses one if it has one.
     *
     * @return the mechanism of each MCP Server that has one, by {@code McpApplication} subclass, created on demand
     */
    private static Map<Class<?>, Supplier<Mechanism>> chooseMechanisms(Map<Class<?>, Authenticator> authenticators,
            List<String> problems) {
        Map<Class<?>, Supplier<Mechanism>> mechanisms = new HashMap<>();
        for (Declaration declaration : declarations.declarations()) {
            Class<?> application = declaration.application();
            String where = "McpApplication " + application.getName() + " (MCP Server '"
                    + declaration.settings().wireName() + "')";
            String key = McpServerSettings.keyPrefix(declaration.settings().name()) + AuthenticatorSetting.KEY;
            Authenticator authenticator = authenticators.get(application);
            AuthenticatorSetting setting = declaration.authenticator();
            if (setting instanceof AuthenticatorSetting.Bean || setting instanceof AuthenticatorSetting.Unset) {
                if (authenticator != null) {
                    mechanisms.put(application, () -> new BeanMechanism(authenticator));
                } else if (setting instanceof AuthenticatorSetting.Bean) {
                    problems.add(where + ": " + key + " is bean, but no McpAuthenticator is bound to it; declare "
                            + "one with " + (McpServer.DEFAULT.equals(declaration.settings().name()) ? "no @McpServer"
                                    : "@McpServer(\"" + declaration.settings().name() + "\")"));
                }
            } else if (authenticator != null) {
                problems.add(where + " has " + authenticator.describe() + ", but " + key + " isn't bean; set it "
                        + "to bean, or remove the McpAuthenticator");
            } else if (setting instanceof AuthenticatorSetting.OAuth oauth) {
                mechanisms.put(application, () -> new OAuthMechanism(oauth.protection()));
            } else if (setting instanceof AuthenticatorSetting.Basic basic) {
                String realm = declaration.settings().wireName();
                mechanisms.put(application, () -> new BasicMechanism(realm, basic.users()));
            }
        }
        return mechanisms;
    }

    private static String display(String server) {
        return McpServer.DEFAULT.equals(server) ? "default" : server;
    }
}
