package io.github.jungm.crema.internal.security;

import java.util.LinkedHashSet;
import java.util.Set;

import org.mcpjava.server.McpServer;

import io.github.jungm.crema.McpAuthenticator;
import io.github.jungm.crema.internal.model.InstanceSource;

/**
 * An {@link McpAuthenticator} bean and the MCP Servers it is bound to.
 *
 * @param beanClass the bean class, for messages
 * @param servers the names of the MCP Servers, from {@code @McpServer} on the bean class; the default MCP Server
 *        without it
 * @param instances obtains the authenticator, once per request
 */
public record Authenticator(Class<?> beanClass, Set<String> servers, InstanceSource instances) {

    public Authenticator {
        servers = Set.copyOf(servers);
    }

    /**
     * The authenticator of a bean class, bound as its {@code @McpServer} annotations say.
     */
    public static Authenticator of(Class<?> beanClass, InstanceSource instances) {
        Set<String> servers = new LinkedHashSet<>();
        for (McpServer server : beanClass.getAnnotationsByType(McpServer.class)) {
            servers.add(server.value());
        }
        if (servers.isEmpty()) {
            servers.add(McpServer.DEFAULT);
        }
        return new Authenticator(beanClass, servers, instances);
    }

    /**
     * Whether a bean class is an authenticator.
     */
    public static boolean is(Class<?> beanClass) {
        return McpAuthenticator.class.isAssignableFrom(beanClass);
    }

    public String describe() {
        return "McpAuthenticator " + beanClass.getName();
    }
}
