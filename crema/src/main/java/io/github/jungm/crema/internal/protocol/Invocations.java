package io.github.jungm.crema.internal.protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mcpjava.server.FeatureType;
import org.mcpjava.server.McpException;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.ResourceResponse;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.internal.bind.BindingException;
import io.github.jungm.crema.internal.invoke.CompletionContextImpl;
import io.github.jungm.crema.internal.invoke.Invocation;
import io.github.jungm.crema.internal.invoke.ReturnConversion;
import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.Param;
import io.github.jungm.crema.internal.security.AccessPolicy.RejectedException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * The methods that invoke Feature Methods and Completion Methods: {@code tools/call}, {@code resources/read},
 * {@code prompts/get} and {@code completion/complete}.
 */
final class Invocations {

    private static final Logger LOG = Logger.getLogger(Invocations.class.getName());

    private Invocations() {
    }

    /**
     * The Feature Method a request invokes, if the request names one that exists.
     */
    static Optional<Feature> target(Call call) {
        switch (call.request().method()) {
            case "tools/call":
                return Json.string(call.params(), "name").flatMap(call.server()::tool).map(Feature.class::cast);
            case "resources/read":
                return Json.string(call.params(), "uri")
                        .flatMap(uri -> resource(call, uri)).map(Target::feature);
            case "prompts/get":
                return Json.string(call.params(), "name")
                        .flatMap(call.server()::prompt).map(Feature.class::cast);
            default:
                return Optional.empty();
        }
    }

    static JsonObject callTool(Call call) {
        String name = call.requireString("name");
        Feature.Tool tool = call.server().tool(name)
                .orElseThrow(() -> McpError.invalidParams("Unknown tool: " + name));
        checkAccess(call, tool);
        JsonObject arguments = call.optionalObject("arguments").orElse(null);
        var mapping = call.services().mapping();
        ToolResponse response;
        try {
            Object value = call.invocation(null).invoke(tool.method(),
                    Invocation.fromJson(arguments, mapping.binder()));
            response = ReturnConversion.tool(value, tool.structuredContent(), call.services().encoders(),
                    mapping.jsonb());
        } catch (BindingException e) {
            response = ToolResponse.ofError("Invalid arguments for tool " + name + ": " + e.getMessage());
        } catch (McpException e) {
            response = ToolResponse.ofError(e.getMessage() != null ? e.getMessage() : "Tool " + name + " failed");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tool " + name + " (" + tool.method() + ") failed", e);
            response = ToolResponse.ofError("Tool " + name + " failed with an internal error");
        }
        return ProtocolJson.toolResult(response, mapping.jsonb()::toJsonValue);
    }

    static JsonObject readResource(Call call) {
        String uri = call.requireString("uri");
        Target target = resource(call, uri).orElseThrow(() -> notFound(uri));
        checkAccess(call, target.feature());
        var mapping = call.services().mapping();
        Object value = invoke(call, target.feature(), Invocation.fromStrings(target.variables(), mapping.binder()),
                null, e -> McpError.internal("Invalid resource URI " + uri + ": " + e.getMessage()));
        if (value == null) {
            throw notFound(uri);
        }
        ResourceResponse response;
        try {
            response = ReturnConversion.resource(value, uri, target.mimeType(), mapping.jsonb());
        } catch (RuntimeException e) {
            throw internal(target.feature(), e);
        }
        JsonObjectBuilder result = Json.object()
                .add("contents", ProtocolJson.resourceContents(response, mapping.jsonb()::toJsonValue));
        addMeta(result, response.metadata(), call);
        return Listings.cacheable(result, call, 0).build();
    }

    static JsonObject getPrompt(Call call) {
        String name = call.requireString("name");
        Feature.Prompt prompt = call.server().prompt(name)
                .orElseThrow(() -> McpError.invalidParams("Unknown prompt: " + name));
        checkAccess(call, prompt);
        Map<String, String> arguments = strings(call.optionalObject("arguments"), "Prompt arguments");
        var mapping = call.services().mapping();
        Object value = invoke(call, prompt, Invocation.fromStrings(arguments, mapping.binder()), null,
                e -> McpError.invalidParams("Invalid arguments for prompt " + name + ": " + e.getMessage()));
        PromptResponse response;
        try {
            response = ReturnConversion.prompt(value);
        } catch (RuntimeException e) {
            throw internal(prompt, e);
        }
        return ProtocolJson.promptResult(response, mapping.jsonb()::toJsonValue);
    }

    static JsonObject complete(Call call) {
        JsonObject ref = call.optionalObject("ref")
                .orElseThrow(() -> McpError.invalidParams("Missing required object parameter: ref"));
        JsonObject argument = call.optionalObject("argument")
                .orElseThrow(() -> McpError.invalidParams("Missing required object parameter: argument"));
        String argumentName = requireString(argument, "argument.name");
        String argumentValue = requireString(argument, "argument.value");
        Map<String, String> context = strings(call.optionalObject("context")
                .flatMap(c -> Optional.ofNullable(c.get("arguments")).filter(v -> v != JsonValue.NULL).map(v -> {
                    if (v instanceof JsonObject object) {
                        return object;
                    }
                    throw McpError.invalidParams("context.arguments must be an object");
                })), "context.arguments");
        String type = requireString(ref, "ref.type");
        Feature target;
        Optional<Feature.Completion> completion;
        if (type.equals("ref/prompt")) {
            String name = requireString(ref, "ref.name");
            target = call.server().prompt(name).orElseThrow(() -> McpError.invalidParams("Unknown prompt: " + name));
            completion = call.server().completion(FeatureType.PROMPT, name, argumentName);
        } else if (type.equals("ref/resource")) {
            String uri = requireString(ref, "ref.uri");
            Optional<Feature.ResourceTemplate> template = call.server().resourceTemplates().stream()
                    .filter(t -> t.uriTemplate().template().equals(uri)).findFirst();
            if (template.isEmpty()) {
                target = call.server().resource(uri)
                        .orElseThrow(() -> McpError.invalidParams("Unknown resource template: " + uri));
                completion = Optional.empty();
            } else {
                target = template.get();
                completion = call.server().completion(FeatureType.RESOURCE_TEMPLATE, template.get().name(),
                        argumentName);
            }
        } else {
            throw McpError.invalidParams("Unknown reference type: " + type);
        }
        checkAccess(call, target);
        CompletionResult result = CompletionResult.builder().setHasMore(false).build();
        if (completion.isPresent()) {
            checkAccess(call, completion.get());
            Object value = invoke(call, completion.get(),
                    Invocation.fromStrings(Map.of(argumentName, argumentValue), call.services().mapping().binder()),
                    new CompletionContextImpl(context), e -> McpError.invalidParams(e.getMessage()));
            try {
                result = ReturnConversion.completion(value);
            } catch (RuntimeException e) {
                throw internal(completion.get(), e);
            }
        }
        JsonObjectBuilder json = Json.object()
                .add("completion", ProtocolJson.completion(result));
        addMeta(json, result.metadata(), call);
        return json.build();
    }

    private static Object invoke(Call call, Feature feature, Function<Param.Argument, Object> arguments,
            CompletionContextImpl context, Function<BindingException, McpError> bindingError) {
        try {
            return call.invocation(context).invoke(feature.method(), arguments);
        } catch (BindingException e) {
            throw bindingError.apply(e);
        } catch (McpException e) {
            throw McpError.internal(e.getMessage() != null ? e.getMessage() : "Internal error");
        } catch (Exception e) {
            throw internal(feature, e);
        }
    }

    private static McpError internal(Feature feature, Exception e) {
        LOG.log(Level.WARNING, feature.method() + " failed", e);
        return McpError.internal("Internal error");
    }

    private static void checkAccess(Call call, Feature feature) {
        var access = call.services().access();
        if (!access.permits(call.server(), feature, call.caller())) {
            throw new RejectedException(access.forbidden(call.server(), call.caller()));
        }
    }

    private static McpError notFound(String uri) {
        return new McpError(McpError.INVALID_PARAMS, "Resource not found",
                Json.object().add("uri", uri).build(), 200);
    }

    private record Target(Feature feature, String mimeType, Map<String, String> variables) {
    }

    private static Optional<Target> resource(Call call, String uri) {
        Optional<Feature.Resource> resource = call.server().resource(uri);
        if (resource.isPresent()) {
            return Optional.of(new Target(resource.get(), resource.get().mimeType(), Map.of()));
        }
        for (Feature.ResourceTemplate template : call.server().resourceTemplates()) {
            Optional<Map<String, String>> variables = template.uriTemplate().match(uri);
            if (variables.isPresent()) {
                return Optional.of(new Target(template, template.mimeType(), variables.get()));
            }
        }
        return Optional.empty();
    }

    private static String requireString(JsonObject json, String path) {
        String key = path.substring(path.lastIndexOf('.') + 1);
        return Json.string(json, key)
                .orElseThrow(() -> McpError.invalidParams("Missing required string parameter: " + path));
    }

    private static Map<String, String> strings(Optional<JsonObject> json, String what) {
        Map<String, String> strings = new HashMap<>();
        json.ifPresent(object -> object.forEach((key, value) -> {
            if (!(value instanceof JsonString string)) {
                throw McpError.invalidParams(what + " must be strings, but " + key + " is " + value);
            }
            strings.put(key, string.getString());
        }));
        return strings;
    }

    private static void addMeta(JsonObjectBuilder result, Map<String, Object> metadata, Call call) {
        if (!metadata.isEmpty()) {
            result.add("_meta", ProtocolJson.meta(metadata, call.services().mapping().jsonb()::toJsonValue));
        }
    }
}
