package io.github.jungm.crema.internal.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.completion.CompleteResourceTemplate;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpServerInfo;
import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.CremaSettings;
import io.github.jungm.crema.internal.config.ServerSettings;
import io.github.jungm.crema.internal.invoke.Mapping;

/**
 * Deployment validation that needs all Features and MCP Servers.
 */
class ServerRegistryTest {

    private static final Mapping MAPPING = Mapping.create();

    static class DefaultApp {
    }

    @McpServerInfo(name = "admin")
    static class AdminApp {
    }

    @McpServerInfo(name = "admin")
    static class OtherAdminApp {
    }

    public static class A {
        @Tool
        public void tool() {
        }

        @Resource(uri = "test://same", name = "first")
        public String first() {
            return null;
        }

        @Prompt
        public String prompt(String topic) {
            return null;
        }

        @ResourceTemplate(uriTemplate = "x://{id}")
        public String template(String id) {
            return null;
        }

        @CompletePrompt("prompt")
        public String completeTopic(String topic) {
            return null;
        }
    }

    public static class B {
        @Tool(name = "tool")
        public void sameName() {
        }

        @Resource(uri = "test://same", name = "second")
        public String second() {
            return null;
        }

        @CompletePrompt("prompt")
        public String completeTopicAgain(@CompleteArg(name = "topic") String value) {
            return null;
        }

        @CompletePrompt("missing")
        public String unknownPrompt(String topic) {
            return null;
        }

        @CompletePrompt("prompt")
        public String unknownArgument(String subject) {
            return null;
        }

        @CompleteResourceTemplate("template")
        public String unknownVariable(String key) {
            return null;
        }

        @Tool
        @McpServer("nowhere")
        public void unbound() {
        }
    }

    public static class C {
        @ResourceTemplate(uriTemplate = "x://{key}")
        public String sameShape(String key) {
            return null;
        }

        @ResourceTemplate(uriTemplate = "x://{key}/more")
        public String otherShape(String key) {
            return null;
        }
    }

    @Test
    void validRegistry() {
        ServerRegistry.Result result = build(List.of(declaration(DefaultApp.class)), A.class);
        assertEquals(List.of(), result.problems());
        assertEquals(List.of(), result.warnings());
        McpServerModel server = result.registry().server(DefaultApp.class).orElseThrow();
        assertEquals("default", server.info().name());
        assertEquals("0.0.0", server.info().version());
        assertTrue(server.tool("tool").isPresent());
        assertTrue(server.completion(org.mcpjava.server.FeatureType.PROMPT, "prompt", "topic").isPresent());
        // lookups also work for subclasses, such as CDI proxies of the McpApplication
        class Proxy extends DefaultApp {
        }
        assertEquals(Optional.of(server), result.registry().server(Proxy.class));
    }

    @Test
    void crossChecks() {
        List<String> problems = build(List.of(declaration(DefaultApp.class)), A.class, B.class).problems();
        String b = B.class.getName() + "#";
        assertProblem(problems, "@Tool method " + b + "sameName(): duplicate Tool name 'tool' in MCP Server "
                + "'default', also used by " + A.class.getName() + "#tool()");
        assertProblem(problems, "@Resource method " + b + "second(): duplicate resource URI 'test://same'");
        assertProblem(problems, "@CompletePrompt method " + b + "completeTopicAgain(String): duplicate "
                + "Completion Method for prompt 'prompt', argument 'topic'");
        assertProblem(problems, "@CompletePrompt method " + b + "unknownPrompt(String): references the Prompt "
                + "'missing', which doesn't exist in MCP Server 'default'");
        assertProblem(problems, "@CompletePrompt method " + b + "unknownArgument(String): completes the Argument "
                + "'subject', but the Prompt 'prompt' only has the Arguments [topic]");
        assertProblem(problems, "@CompleteResourceTemplate method " + b + "unknownVariable(String): completes the "
                + "Argument 'key', but the Resource Template 'template' only has the Arguments [id]");
        assertProblem(problems, "@Tool method " + b + "unbound(): is bound to the MCP Server 'nowhere', but no "
                + "McpApplication declares it");
        assertEquals(7, problems.size(), problems.toString());
    }

    @Test
    void templatesOfTheSameShapeAreWarnings() {
        ServerRegistry.Result result = build(List.of(declaration(DefaultApp.class)), A.class, C.class);
        assertEquals(List.of(), result.problems());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        String warning = result.warnings().get(0);
        assertTrue(warning.contains("'x://{key}' matches the same URIs as 'x://{id}'")
                || warning.contains("'x://{id}' matches the same URIs as 'x://{key}'"), warning);
    }

    @Test
    void featuresNeedAnMcpApplication() {
        List<String> problems = build(List.of(), A.class).problems();
        assertProblem(problems, "@Tool method " + A.class.getName() + "#tool(): is bound to the MCP Server "
                + "'default', but no McpApplication declares it; declare one with @ApplicationPath(\"mcp\")");
    }

    @Test
    void serverNamesAreUnique() {
        List<String> problems = build(List.of(declaration(AdminApp.class), declaration(OtherAdminApp.class)))
                .problems();
        assertEquals(List.of("McpApplication " + OtherAdminApp.class.getName() + " declares the MCP Server 'admin', "
                + "which McpApplication " + AdminApp.class.getName() + " declares already; set a different "
                + "@McpServerInfo(name = ...)"), problems);
    }

    private static ServerRegistry.Declaration declaration(Class<?> application) {
        return new ServerRegistry.Declaration(application, ServerSettings.resolve(
                application.getAnnotation(McpServerInfo.class), ConfigLookup.none(), Optional::empty), List.of());
    }

    private static ServerRegistry.Result build(List<ServerRegistry.Declaration> declarations, Class<?>... beans) {
        FeatureScanner scanner = new FeatureScanner(MAPPING, IconLookup.reflective());
        for (Class<?> bean : beans) {
            Scanning.scan(scanner, bean, null);
        }
        assertEquals(List.of(), scanner.problems());
        return ServerRegistry.build(new ArrayList<>(declarations), scanner.features(), CremaSettings.defaults());
    }

    private static void assertProblem(List<String> problems, String text) {
        assertTrue(problems.stream().anyMatch(p -> p.startsWith(text)),
                () -> "No problem starting with '" + text + "' in:\n" + String.join("\n", problems));
    }
}
