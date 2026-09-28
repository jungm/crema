package io.github.jungm.crema.internal.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;
import org.mcpjava.server.Icons;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.MetaField;
import org.mcpjava.server.Role;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.completion.CompletionContext;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.internal.TestDeployment;
import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonObject;

/**
 * Deployment validation of single methods, and the definitions of valid Features.
 */
class FeatureScannerTest {

    public record Weather(double temperature, String conditions) {
    }

    public static class ToolIcons implements IconProvider {
        @Override
        public List<Icon> getIcons(FeatureType featureType, String name) {
            return List.of(Icon.of("https://example.com/" + featureType + "/" + name + ".png", "image/png"));
        }
    }

    @Icons(iconProvider = ToolIcons.class)
    public static class Valid {

        @Tool(title = "Weather", description = "Current weather",
                annotations = @Tool.Annotations(readOnlyHint = true, openWorldHint = false),
                structuredContent = true)
        @MetaField(prefix = "com.example/", name = "owner", value = "team-x")
        @MetaField(name = "version", type = MetaField.Type.INT, value = "2")
        @MetaField(name = "config", type = MetaField.Type.JSON, value = "{\"a\":[1,true]}")
        public CompletableFuture<Weather> weather(@ToolArg(description = "City name") String city,
                @ToolArg(required = false) Integer days, Optional<String> units,
                @ToolArg(defaultValue = "3") int count) {
            return null;
        }

        @Tool(name = "no_args")
        public void noArgs() {
        }

        @Resource(uri = "test://text", mimeType = "text/plain", size = 5,
                annotations = @Resource.Annotations(audience = Role.USER, priority = 0.5,
                        lastModified = "2026-01-01T00:00:00Z"))
        public String text() {
            return "hello";
        }

        @ResourceTemplate(name = "row", uriTemplate = "db:///{table}/{id}",
                annotations = @Resource.Annotations(lastModified = "2026-01-01T02:00:00+02:00"))
        public String row(String id, String table) {
            return null;
        }

        @Prompt(description = "Review")
        public PromptMessage review(@PromptArg(title = "Code", description = "The code") String code,
                @PromptArg(required = false) String language) {
            return null;
        }

        @CompletePrompt("review")
        public List<String> completeLanguage(@CompleteArg(name = "language") String value,
                CompletionContext context) {
            return List.of();
        }
    }

    @Test
    void definitions() {
        FeatureScanner scanner = scan(Valid.class);
        assertEquals(List.of(), scanner.problems());
        Map<String, JsonObject> byName = new HashMap<>();
        scanner.features().forEach(f -> {
            if (f instanceof Feature.Tool t) {
                byName.put(t.name(), t.definition());
            } else if (f instanceof Feature.Resource r) {
                byName.put(r.name(), r.definition());
            } else if (f instanceof Feature.ResourceTemplate t) {
                byName.put(t.name(), t.definition());
            } else if (f instanceof Feature.Prompt p) {
                byName.put(p.name(), p.definition());
            }
        });
        JsonObject weather = byName.get("weather");
        assertEquals("Weather", weather.getString("title"));
        assertEquals("Current weather", weather.getString("description"));
        assertEquals(Json.parse("{\"readOnlyHint\":true,\"openWorldHint\":false}"), weather.get("annotations"));
        assertEquals(Json.parse("{\"com.example/owner\":\"team-x\",\"version\":2,\"config\":{\"a\":[1,true]}}"),
                weather.get("_meta"));
        assertEquals(Json.parse("[{\"src\":\"https://example.com/TOOL/weather.png\",\"mimeType\":\"image/png\"}]"),
                weather.get("icons"));
        JsonObject input = weather.getJsonObject("inputSchema");
        assertEquals("object", input.getString("type"));
        assertEquals(Json.parse("[\"city\"]"), input.get("required"));
        assertEquals("City name", input.getJsonObject("properties").getJsonObject("city").getString("description"));
        assertEquals("object", weather.getJsonObject("outputSchema").getString("type"));

        JsonObject noArgs = byName.get("no_args");
        assertEquals("object", noArgs.getJsonObject("inputSchema").getString("type"));
        assertFalse(noArgs.containsKey("annotations"));
        assertFalse(noArgs.containsKey("outputSchema"));

        assertEquals(Json.parse("""
                {"uri":"test://text","name":"text","mimeType":"text/plain",
                 "annotations":{"audience":["user"],"priority":0.5,"lastModified":"2026-01-01T00:00:00Z"},
                 "size":5,"icons":[{"src":"https://example.com/RESOURCE/text.png","mimeType":"image/png"}]}
                """), byName.get("text"));
        assertEquals("db:///{table}/{id}", byName.get("row").getString("uriTemplate"));
        assertEquals(Json.parse("{\"lastModified\":\"2026-01-01T00:00:00Z\"}"), byName.get("row").get("annotations"));
        assertEquals(Json.parse("""
                [{"name":"code","title":"Code","description":"The code","required":true},
                 {"name":"language","required":false}]
                """), byName.get("review").get("arguments"));
    }

    @Test
    void bindsToServersOfMethodAndDeclaringClass() {
        @McpServer("a")
        class Bound {
            @Tool
            @McpServer("b")
            public void both() {
            }

            @Tool
            public void classOnly() {
            }
        }
        class Unbound {
            @Tool
            public void tool() {
            }
        }
        FeatureScanner scanner = Scanning.scan(scan(Bound.class), Unbound.class, null);
        Map<String, Set<String>> servers = new HashMap<>();
        scanner.features().forEach(f -> servers.put(f.name(), f.method().servers()));
        assertEquals(Set.of("a", "b"), servers.get("both"));
        assertEquals(Set.of("a"), servers.get("classOnly"));
        assertEquals(Set.of(McpServer.DEFAULT), servers.get("tool"));
    }

    public static class Invalid {

        @Tool
        @Prompt
        public String twoKinds() {
            return null;
        }

        @Tool
        private String hidden() {
            return null;
        }

        @ResourceTemplate(uriTemplate = "db:///{table}/{id}")
        public String wrongVariables(String table, String key) {
            return null;
        }

        @ResourceTemplate(uriTemplate = "db:///{id}")
        public String nonStringVariable(int id) {
            return null;
        }

        @ResourceTemplate(uriTemplate = "db:///{+id}")
        public String levelTwo(String id) {
            return null;
        }

        @Resource(uri = "test://x")
        public String resourceWithArgument(String x) {
            return null;
        }

        @Prompt
        public Integer badPrompt() {
            return null;
        }

        @Prompt
        public List<String> badPromptList() {
            return null;
        }

        @CompletePrompt("p")
        public int badCompletion(String value) {
            return 0;
        }

        @CompletePrompt("p")
        public String twoArguments(String a, String b) {
            return null;
        }

        @CompletePrompt("p")
        public String nonStringArgument(int a) {
            return null;
        }

        @Tool(structuredContent = true)
        public ToolResponse structuredResponse() {
            return null;
        }

        @Tool(structuredContent = true)
        public void structuredVoid() {
        }

        @Tool
        public void completionContext(CompletionContext context) {
        }

        @Tool
        public void primitiveOptional(@ToolArg(required = false) int count) {
        }

        @Tool
        public void badDefault(@ToolArg(defaultValue = "many") int count) {
        }

        @Tool
        public void duplicateArguments(@ToolArg(name = "x") String a, @ToolArg(name = "x") String b) {
        }

        @Tool
        @MetaField(prefix = "io.modelcontextprotocol/", name = "x", value = "1")
        public void reservedPrefix() {
        }

        @Tool
        @MetaField(prefix = "tools.mcp.com/", name = "x", value = "1")
        public void reservedPrefix2() {
        }

        @Tool
        @MetaField(prefix = "example", name = "x", value = "1")
        public void invalidPrefix() {
        }

        @Tool
        @MetaField(name = "-x", value = "1")
        public void invalidName() {
        }

        @Tool
        @MetaField(name = "x", type = MetaField.Type.INT, value = "one")
        public void invalidInt() {
        }

        @Tool
        @MetaField(name = "x", type = MetaField.Type.BOOLEAN, value = "yes")
        public void invalidBoolean() {
        }

        @Tool
        @MetaField(name = "x", type = MetaField.Type.JSON, value = "{")
        public void invalidJson() {
        }

        @Resource(uri = "test://y", annotations = @Resource.Annotations(priority = 2))
        public String invalidPriority() {
            return null;
        }

        @Resource(uri = "test://y", annotations = @Resource.Annotations(priority = -0.5))
        public String negativePriority() {
            return null;
        }

        @Resource(uri = "test://y", annotations = @Resource.Annotations(lastModified = "2026-01-01T00:00:00"))
        public String localLastModified() {
            return null;
        }

        @Tool
        @MetaField(prefix = "com.example/", name = "x", value = "1")
        @MetaField(prefix = "com.example/", name = "x", value = "2")
        public void duplicateMetaField() {
        }
    }

    @Test
    void problemsNameClassAndMethod() {
        FeatureScanner scanner = scan(Invalid.class);
        assertEquals(List.of(), scanner.features().stream().map(Feature::name).toList());
        List<String> problems = scanner.problems();
        String prefix = Invalid.class.getName() + "#";
        assertProblem(problems, prefix + "twoKinds()", "more than one of @Tool, @Prompt");
        assertProblem(problems, prefix + "hidden()", "must not be private");
        assertProblem(problems, prefix + "wrongVariables(String, String)",
                "the variables [table, id] of URI template 'db:///{table}/{id}' don't match its String "
                        + "parameters [table, key]");
        assertProblem(problems, prefix + "nonStringVariable(int)", "parameter 'id' must be a String");
        assertProblem(problems, prefix + "levelTwo(String)", "isn't a Level 1 variable");
        assertProblem(problems, prefix + "resourceWithArgument(String)", "Resource methods take no Arguments");
        assertProblem(problems, prefix + "badPrompt()", "Prompt methods must return String, PromptMessage, "
                + "List<PromptMessage> or PromptResponse, not java.lang.Integer");
        assertProblem(problems, prefix + "badPromptList()", "not java.util.List<java.lang.String>");
        assertProblem(problems, prefix + "badCompletion(String)",
                "Completion Methods must return String, List<String> or CompletionResult, not int");
        assertProblem(problems, prefix + "twoArguments(String, String)", "exactly one String Argument");
        assertProblem(problems, prefix + "nonStringArgument(int)", "exactly one String Argument");
        assertProblem(problems, prefix + "structuredResponse()", "set outputSchemaFrom");
        assertProblem(problems, prefix + "structuredVoid()", "requires a return type or outputSchemaFrom");
        assertProblem(problems, prefix + "completionContext(CompletionContext)",
                "CompletionContext is only available to Completion Methods");
        assertProblem(problems, prefix + "primitiveOptional(int)", "needs a defaultValue or a wrapper type");
        assertProblem(problems, prefix + "badDefault(int)", "the defaultValue 'many' of Argument 'count'");
        assertProblem(problems, prefix + "duplicateArguments(String, String)", "Argument name 'x'");
        assertProblem(problems, prefix + "reservedPrefix()", "is reserved for MCP");
        assertProblem(problems, prefix + "reservedPrefix2()", "is reserved for MCP");
        assertProblem(problems, prefix + "invalidPrefix()", "must be dot-separated labels followed by '/'");
        assertProblem(problems, prefix + "invalidName()", "@MetaField name '-x'");
        assertProblem(problems, prefix + "invalidInt()", "isn't an integer");
        assertProblem(problems, prefix + "invalidBoolean()", "neither 'true' nor 'false'");
        assertProblem(problems, prefix + "invalidJson()", "isn't valid JSON");
        assertProblem(problems, prefix + "invalidPriority()", "between 0.0 and 1.0");
        assertProblem(problems, prefix + "negativePriority()", "between 0.0 and 1.0");
        assertProblem(problems, prefix + "localLastModified()", "isn't an ISO 8601 date-time with offset");
        assertProblem(problems, prefix + "duplicateMetaField()",
                "more than one @MetaField has the key 'com.example/x'");
    }

    public static class AnyStructuredContent {
        @Tool(structuredContent = true)
        public String string() {
            return null;
        }

        @Tool(structuredContent = true)
        public List<Weather> list() {
            return null;
        }

        @Tool(structuredContent = true)
        public Optional<Weather> optional() {
            return null;
        }

        @Tool(structuredContent = true, outputSchemaFrom = int[].class)
        public ToolResponse numbers() {
            return null;
        }
    }

    @Test
    void structuredContentMayBeAnyJsonValue() {
        FeatureScanner scanner = scan(AnyStructuredContent.class);
        assertEquals(List.of(), scanner.problems());
        Map<String, JsonObject> schemas = new HashMap<>();
        scanner.features().forEach(f -> schemas.put(f.name(), ((Feature.Tool) f).definition().getJsonObject("outputSchema")));
        assertEquals(Json.parse("{\"type\":\"string\"}"), schemas.get("string"));
        assertEquals("array", schemas.get("list").getString("type"));
        assertEquals("array", schemas.get("numbers").getString("type"));
        assertTrue(schemas.get("optional").containsKey("anyOf") || schemas.get("optional").containsKey("type"),
                schemas.get("optional")::toString);
    }

    @Test
    void argumentNamesNeedParametersOrAnnotation(@TempDir Path dir) throws Exception {
        Class<?> type = compileWithoutParameterNames(dir, "Unnamed", """
                public class Unnamed {
                    @org.mcpjava.server.tools.Tool
                    public String tool(String text, @org.mcpjava.server.tools.ToolArg(name = "count") int count) {
                        return text;
                    }

                    @org.mcpjava.server.resources.ResourceTemplate(uriTemplate = "x://{id}")
                    public String template(String id) {
                        return id;
                    }
                }
                """);
        List<String> problems = scan(type).problems();
        assertProblem(problems, "Unnamed#tool(String, int)", "the Argument name of parameter 0 (String) can't be "
                + "determined; set @ToolArg(name = ...) or compile with -parameters");
        assertProblem(problems, "Unnamed#template(String)", "set @ResourceTemplateArg(name = ...)");
        assertEquals(2, problems.size(), problems.toString());
    }

    private static FeatureScanner scan(Class<?> type) {
        return Scanning.scan(new FeatureScanner(TestDeployment.MAPPING, IconLookup.reflective()), type, null);
    }

    private static void assertProblem(List<String> problems, String method, String text) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(method + ": ") && p.contains(text)),
                () -> "No problem for " + method + " containing '" + text + "' in:\n" + String.join("\n", problems));
    }

    private static Class<?> compileWithoutParameterNames(Path dir, String name, String source)
            throws IOException, ClassNotFoundException {
        Path file = dir.resolve(name + ".java");
        Files.writeString(file, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d",
                dir.toString(), file.toString());
        assertEquals(0, result);
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, FeatureScannerTest.class.getClassLoader())
                .loadClass(name);
    }
}
