package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mcpjava.server.Icons;

import io.github.jungm.crema.McpApplication;
import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.cdi.CremaDeployment;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HandlesTypes;

/**
 * Finds the {@link McpApplication} subclasses of the web application, resolves the MCP Servers they declare, and
 * fails deployment if they are invalid.
 */
@HandlesTypes(McpApplication.class)
public class CremaInitializer implements ServletContainerInitializer {

    private static final Logger LOG = Logger.getLogger(CremaInitializer.class.getName());
    private static final List<String> APPLICATION_METHODS = List.of("getClasses", "getSingletons", "getProperties");

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) throws ServletException {
        List<Class<?>> types = classes == null ? List.of()
                : classes.stream().filter(CremaInitializer::isMcpApplication)
                        .sorted(Comparator.comparing(Class::getName)).toList();
        List<String> problems = new ArrayList<>();
        types.forEach(type -> checkOverrides(type, problems));
        ConfigLookup config = ConfigLookup.of(context.getClassLoader());
        CremaSettings settings = CremaSettings.defaults();
        try {
            settings = CremaSettings.resolve(config);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        Supplier<Optional<String>> manifestVersion = () -> manifestVersion(context);
        List<CremaDeployment.Application> applications = new ArrayList<>();
        for (Class<?> type : types) {
            Icons icons = type.getAnnotation(Icons.class);
            applications.add(new CremaDeployment.Application(type,
                    ServerSettings.resolve(type.getAnnotation(McpServerInfo.class), config, manifestVersion),
                    icons == null ? null : icons.iconProvider()));
        }
        if (problems.isEmpty()) {
            problems.addAll(CremaDeployment.applicationsDiscovered(
                    new CremaDeployment.Applications(applications, settings)));
        }
        if (!problems.isEmpty()) {
            throw new ServletException("Invalid MCP Servers:\n - " + String.join("\n - ", problems));
        }
    }

    static boolean isMcpApplication(Class<?> type) {
        return McpApplication.class.isAssignableFrom(type) && !type.isInterface()
                && !Modifier.isAbstract(type.getModifiers());
    }

    /**
     * Rejects subclasses that change which JAX-RS classes serve MCP traffic.
     */
    static void checkOverrides(Class<?> type, List<String> problems) {
        for (Class<?> c = type; c != McpApplication.class; c = c.getSuperclass()) {
            for (String method : APPLICATION_METHODS) {
                try {
                    c.getDeclaredMethod(method);
                    problems.add("McpApplication " + type.getName() + ": " + c.getName() + " overrides " + method
                            + "(), but only Crema decides which JAX-RS classes serve MCP traffic");
                } catch (NoSuchMethodException e) {
                    // not overridden
                }
            }
        }
    }

    private static Optional<String> manifestVersion(ServletContext context) {
        try (InputStream in = context.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(new Manifest(in).getMainAttributes().getValue("Implementation-Version"))
                    .filter(version -> !version.isBlank());
        } catch (IOException e) {
            LOG.log(Level.FINE, "Can't read the web application's manifest", e);
            return Optional.empty();
        }
    }
}
