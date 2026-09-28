package io.github.jungm.crema.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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
            problems.addAll(CremaDeployment.applicationsDiscovered(context,
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
     * Rejects subclasses that declare methods: overriding {@code getClasses()}, {@code getSingletons()} or
     * {@code getProperties()} changes which JAX-RS classes serve MCP traffic, and on TomEE any declared method
     * switches off provider scanning for the whole web application.
     */
    static void checkOverrides(Class<?> type, List<String> problems) {
        for (Class<?> c = type; c != McpApplication.class; c = c.getSuperclass()) {
            List<String> others = new ArrayList<>();
            for (Method method : c.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (APPLICATION_METHODS.contains(method.getName()) && method.getParameterCount() == 0) {
                    problems.add("McpApplication " + type.getName() + ": " + c.getName() + " overrides "
                            + method.getName() + "(), but only Crema decides which JAX-RS classes serve MCP traffic");
                } else {
                    others.add(method.getName() + "()");
                }
            }
            if (!others.isEmpty()) {
                problems.add("McpApplication " + type.getName() + ": " + c.getName() + " declares the methods "
                        + others.stream().sorted().distinct().toList() + ", but McpApplication subclasses must not "
                        + "declare methods");
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
