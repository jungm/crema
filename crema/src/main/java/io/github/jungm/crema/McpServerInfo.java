package io.github.jungm.crema;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.mcpjava.server.McpServer;

/**
 * Describes the MCP Server that an {@link McpApplication} subclass declares. Without this annotation the
 * subclass declares the default MCP Server with no further information.
 * <p>
 * An empty string means "unset". MicroProfile Config values, where present, override these attributes:
 * {@code crema.default-server.<attr>} for the default MCP Server and {@code crema.servers.<name>.<attr>}
 * for named ones, with {@code <attr>} being {@code title}, {@code version}, {@code description},
 * {@code instructions} or {@code website-url}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface McpServerInfo {

    /**
     * The MCP Server name that Features refer to with {@link McpServer @McpServer}.
     *
     * @return the MCP Server name, {@link McpServer#DEFAULT} for the default MCP Server
     */
    String name() default McpServer.DEFAULT;

    /**
     * A human-readable name shown to users.
     *
     * @return the title
     */
    String title() default "";

    /**
     * The version sent to MCP Clients. When unset, the {@code Implementation-Version} of the web application's
     * {@code META-INF/MANIFEST.MF} applies, else {@code 0.0.0}.
     *
     * @return the version
     */
    String version() default "";

    /**
     * A human-readable description.
     *
     * @return the description
     */
    String description() default "";

    /**
     * Instructions for the model on how to use this MCP Server, sent in {@code server/discover}.
     *
     * @return the instructions
     */
    String instructions() default "";

    /**
     * The URL of a website describing this MCP Server.
     *
     * @return the website URL
     */
    String websiteUrl() default "";
}
