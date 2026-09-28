package io.github.jungm.crema.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.McpServerSettings;
import io.github.jungm.crema.internal.http.McpTransport;
import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.FeatureScanner;
import io.github.jungm.crema.internal.model.IconLookup;
import io.github.jungm.crema.internal.model.McpServerModel;
import io.github.jungm.crema.internal.model.Scanning;
import io.github.jungm.crema.internal.model.McpServerRegistry;
import io.github.jungm.crema.internal.protocol.Dispatcher;
import io.github.jungm.crema.internal.protocol.Services;
import io.github.jungm.crema.internal.security.CremaAccessPolicy;

/**
 * Assembles what the CDI extension and the servlet initializer assemble at deployment, without a Runtime: beans are
 * scanned into Features, {@code McpApplication} classes declare MCP Servers, and both are built into a
 * {@link McpServerRegistry} and a {@link Dispatcher}.
 * <p>
 * Applications are declared from their {@link McpServerInfo} (any class works, it needn't extend
 * {@code McpApplication}) without configuration unless {@link #application(Class, ConfigLookup)} supplies some.
 * Feature Methods are invoked on the given bean instances.
 */
public final class TestDeployment {

    /** One {@link Mapping} for all tests; creating a {@code Jsonb} is slow. */
    public static final Mapping MAPPING = Mapping.create();

    private final List<McpServerRegistry.Declaration> declarations = new ArrayList<>();
    private final FeatureScanner scanner = new FeatureScanner(MAPPING, IconLookup.reflective());
    private CremaSettings settings = CremaSettings.defaults();
    private ContentEncoders encoders = new ContentEncoders(List::of);

    private TestDeployment() {
    }

    public static TestDeployment create() {
        return new TestDeployment();
    }

    /**
     * Declares the MCP Server of an application class, without configuration.
     */
    public TestDeployment application(Class<?> application) {
        return application(application, ConfigLookup.none());
    }

    public TestDeployment application(Class<?> application, ConfigLookup config) {
        declarations.add(new McpServerRegistry.Declaration(application, McpServerSettings.resolve(
                application.getAnnotation(McpServerInfo.class), config, Optional::empty), List.of()));
        return this;
    }

    /**
     * Scans the methods {@code type} declares; Feature Methods are invoked on {@code instance}.
     */
    public TestDeployment bean(Class<?> type, Object instance) {
        Scanning.scan(scanner, type, instance);
        return this;
    }

    public TestDeployment settings(CremaSettings settings) {
        this.settings = settings;
        return this;
    }

    public TestDeployment encoders(ContentEncoders encoders) {
        this.encoders = encoders;
        return this;
    }

    /**
     * The scanner, holding the Features and problems of the beans scanned so far.
     */
    public FeatureScanner scanner() {
        return scanner;
    }

    /**
     * Builds the registry, whatever problems it has. The beans must scan without problems.
     */
    public McpServerRegistry.Result build() {
        assertEquals(List.of(), scanner.problems(), "problems scanning the beans");
        return McpServerRegistry.build(declarations, scanner.features(), settings);
    }

    /**
     * Builds the registry, which must have no problems.
     */
    public McpServerRegistry registry() {
        McpServerRegistry.Result result = build();
        assertEquals(List.of(), result.problems(), "problems building the registry");
        return result.registry();
    }

    /**
     * The MCP Server of an application, from a registry without problems.
     */
    public McpServerModel server(Class<?> application) {
        return registry().server(application).orElseThrow();
    }

    /**
     * A dispatcher with Crema's {@link Mapping}, the {@link #encoders(ContentEncoders) encoders} and an access
     * policy.
     */
    public Dispatcher dispatcher(CremaAccessPolicy access) {
        return new Dispatcher(new Services(MAPPING, encoders, access));
    }

    /**
     * A dispatcher whose MCP Servers, from a registry without problems, are all open.
     */
    public Dispatcher dispatcher() {
        return dispatcher(openPolicy(registry()));
    }

    /**
     * The transport of a registry without problems, whose MCP Servers are all open.
     */
    public McpTransport transport() {
        McpServerRegistry registry = registry();
        return new McpTransport(registry, dispatcher(openPolicy(registry)));
    }

    private static CremaAccessPolicy openPolicy(McpServerRegistry registry) {
        CremaAccessPolicy.Result policy = CremaAccessPolicy.create(registry.servers(), Map.of());
        assertEquals(List.of(), policy.problems(), "problems building the access policy");
        return policy.policy();
    }
}
