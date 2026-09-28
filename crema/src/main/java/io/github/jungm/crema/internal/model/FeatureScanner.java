package io.github.jungm.crema.internal.model;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import org.mcpjava.server.Cancellation;
import org.mcpjava.server.FeatureType;
import org.mcpjava.server.Icon;
import org.mcpjava.server.Icons;
import org.mcpjava.server.McpRequest;
import org.mcpjava.server.McpServer;
import org.mcpjava.server.MetaField;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.completion.CompleteResourceTemplate;
import org.mcpjava.server.completion.CompletionContext;
import org.mcpjava.server.completion.CompletionResult;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptMessage;
import org.mcpjava.server.prompts.PromptResponse;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import io.github.jungm.crema.McpCaller;
import io.github.jungm.crema.internal.bind.Types;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.schema.SchemaProperty;
import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Turns annotated methods into {@link Feature}s, collecting a deployment problem for each method that can't
 * become one. Problems name the offending class and method.
 */
public final class FeatureScanner {

    private static final List<Class<? extends Annotation>> FEATURE_ANNOTATIONS = List.of(Tool.class,
            Resource.class, ResourceTemplate.class, Prompt.class, CompletePrompt.class,
            CompleteResourceTemplate.class);

    private final Mapping mapping;
    private final IconLookup icons;
    private final List<Feature> features = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    public FeatureScanner(Mapping mapping, IconLookup icons) {
        this.mapping = mapping;
        this.icons = icons;
    }

    /**
     * Whether a method carries a Feature or completion annotation.
     */
    public static boolean isFeatureMethod(Method method) {
        return FEATURE_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    /**
     * Scans one method; does nothing if it carries no Feature or completion annotation.
     */
    public FeatureScanner scan(Class<?> beanClass, Method method, InstanceSource instances) {
        List<Class<? extends Annotation>> kinds = FEATURE_ANNOTATIONS.stream().filter(method::isAnnotationPresent)
                .toList();
        if (kinds.isEmpty()) {
            return this;
        }
        String where = "@" + kinds.get(0).getSimpleName() + " method " + FeatureMethod.describe(beanClass, method);
        List<String> errors = new ArrayList<>();
        if (kinds.size() > 1) {
            errors.add("carries more than one of " + kinds.stream().map(k -> "@" + k.getSimpleName())
                    .collect(Collectors.joining(", ")));
        } else if (Modifier.isPrivate(method.getModifiers())) {
            errors.add("must not be private");
        } else {
            Class<? extends Annotation> kind = kinds.get(0);
            List<Param> params = params(method, kind, errors);
            FeatureMethod featureMethod = new FeatureMethod(beanClass, method, params, servers(method), instances);
            try {
                Feature feature = feature(kind, method, featureMethod, errors);
                if (errors.isEmpty()) {
                    features.add(feature);
                }
            } catch (RuntimeException e) {
                errors.add(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
        errors.forEach(error -> problems.add(where + ": " + error));
        return this;
    }

    public List<Feature> features() {
        return List.copyOf(features);
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    private Feature feature(Class<? extends Annotation> kind, Method method, FeatureMethod featureMethod,
            List<String> errors) {
        if (kind == Tool.class) {
            return tool(method.getAnnotation(Tool.class), method, featureMethod, errors);
        } else if (kind == Resource.class) {
            return resource(method.getAnnotation(Resource.class), method, featureMethod, errors);
        } else if (kind == ResourceTemplate.class) {
            return resourceTemplate(method.getAnnotation(ResourceTemplate.class), method, featureMethod, errors);
        } else if (kind == Prompt.class) {
            return prompt(method.getAnnotation(Prompt.class), method, featureMethod, errors);
        } else if (kind == CompletePrompt.class) {
            return completion(FeatureType.PROMPT, method.getAnnotation(CompletePrompt.class).value(), method,
                    featureMethod, errors);
        }
        return completion(FeatureType.RESOURCE_TEMPLATE, method.getAnnotation(CompleteResourceTemplate.class).value(),
                method, featureMethod, errors);
    }

    private Feature.Tool tool(Tool tool, Method method, FeatureMethod featureMethod, List<String> errors) {
        String name = name(tool.name(), Tool.ELEMENT_NAME, method);
        JsonObjectBuilder json = describe(Json.object().add("name", name), tool.title(), tool.description());
        json.add("inputSchema", mapping.schemas().inputSchema(featureMethod.arguments().stream()
                .map(a -> new SchemaProperty(a.name(), a.type(), a.description(), a.required())).toList()));
        if (tool.structuredContent()) {
            Type output = tool.outputSchemaFrom() == Void.class ? valueType(method) : tool.outputSchemaFrom();
            Class<?> raw = Types.rawType(output);
            if (raw == void.class || raw == Void.class) {
                errors.add("structuredContent = true requires a return type or outputSchemaFrom");
            } else if (ToolResponse.class.isAssignableFrom(raw)) {
                errors.add("returns ToolResponse with structuredContent = true; set outputSchemaFrom to the type "
                        + "of the structured content");
            } else {
                json.add("outputSchema", mapping.schemas().schemaFor(output));
            }
        }
        JsonObject annotations = toolAnnotations(tool.annotations());
        if (annotations != null) {
            json.add("annotations", annotations);
        }
        finish(json, method, FeatureType.TOOL, name);
        return new Feature.Tool(name, json.build(), featureMethod, tool.structuredContent());
    }

    private Feature.Resource resource(Resource resource, Method method, FeatureMethod featureMethod,
            List<String> errors) {
        if (!featureMethod.arguments().isEmpty()) {
            errors.add("Resource methods take no Arguments, only McpRequest, Progress and Cancellation parameters");
        }
        String name = name(resource.name(), Resource.ELEMENT_NAME, method);
        if (resource.uri().isEmpty()) {
            errors.add("uri must not be empty");
        }
        JsonObjectBuilder json = describe(Json.object().add("uri", resource.uri()).add("name", name),
                resource.title(), resource.description());
        String mimeType = emptyToNull(resource.mimeType());
        if (mimeType != null) {
            json.add("mimeType", mimeType);
        }
        JsonObject annotations = resourceAnnotations(resource.annotations());
        if (annotations != null) {
            json.add("annotations", annotations);
        }
        if (resource.size() >= 0) {
            json.add("size", resource.size());
        }
        finish(json, method, FeatureType.RESOURCE, name);
        return new Feature.Resource(name, resource.uri(), mimeType, json.build(), featureMethod);
    }

    private Feature.ResourceTemplate resourceTemplate(ResourceTemplate template, Method method,
            FeatureMethod featureMethod, List<String> errors) {
        String name = name(template.name(), ResourceTemplate.ELEMENT_NAME, method);
        UriTemplate uriTemplate = UriTemplate.parse(template.uriTemplate());
        Set<String> variables = new LinkedHashSet<>(uriTemplate.variables());
        Set<String> arguments = new LinkedHashSet<>();
        for (Param.Argument argument : featureMethod.arguments()) {
            if (argument.type() != String.class) {
                errors.add("parameter '" + argument.name() + "' must be a String, like every URI template variable");
            }
            arguments.add(argument.name());
        }
        if (errors.isEmpty() && !variables.equals(arguments)) {
            errors.add("the variables " + variables + " of URI template '" + template.uriTemplate()
                    + "' don't match its String parameters " + arguments);
        }
        JsonObjectBuilder json = describe(
                Json.object().add("uriTemplate", template.uriTemplate()).add("name", name), template.title(),
                template.description());
        String mimeType = emptyToNull(template.mimeType());
        if (mimeType != null) {
            json.add("mimeType", mimeType);
        }
        JsonObject annotations = resourceAnnotations(template.annotations());
        if (annotations != null) {
            json.add("annotations", annotations);
        }
        finish(json, method, FeatureType.RESOURCE_TEMPLATE, name);
        return new Feature.ResourceTemplate(name, uriTemplate, mimeType, json.build(), featureMethod);
    }

    private Feature.Prompt prompt(Prompt prompt, Method method, FeatureMethod featureMethod, List<String> errors) {
        Type type = valueType(method);
        if (!isOneOf(type, String.class, PromptMessage.class, PromptResponse.class)
                && !isListOf(type, PromptMessage.class)) {
            errors.add("Prompt methods must return String, PromptMessage, List<PromptMessage> or PromptResponse, "
                    + "not " + type.getTypeName());
        }
        String name = name(prompt.name(), Prompt.ELEMENT_NAME, method);
        JsonObjectBuilder json = describe(Json.object().add("name", name), prompt.title(), prompt.description());
        List<Param.Argument> arguments = featureMethod.arguments();
        if (!arguments.isEmpty()) {
            JsonArrayBuilder array = Json.FACTORY.createArrayBuilder();
            for (Param.Argument argument : arguments) {
                JsonObjectBuilder item = Json.object().add("name", argument.name());
                if (argument.title() != null) {
                    item.add("title", argument.title());
                }
                if (argument.description() != null) {
                    item.add("description", argument.description());
                }
                array.add(item.add("required", argument.required()));
            }
            json.add("arguments", array);
        }
        finish(json, method, FeatureType.PROMPT, name);
        return new Feature.Prompt(name, json.build(), featureMethod);
    }

    private Feature.Completion completion(FeatureType kind, String target, Method method, FeatureMethod featureMethod,
            List<String> errors) {
        Type type = valueType(method);
        if (!isOneOf(type, String.class, CompletionResult.class) && !isListOf(type, String.class)) {
            errors.add("Completion Methods must return String, List<String> or CompletionResult, not "
                    + type.getTypeName());
        }
        List<Param.Argument> arguments = featureMethod.arguments();
        if (arguments.size() != 1 || arguments.get(0).type() != String.class) {
            errors.add("Completion Methods must have exactly one String Argument, the value to complete, but have "
                    + arguments.stream().map(a -> a.type().getTypeName() + " " + a.name()).toList());
            return null;
        }
        return new Feature.Completion(kind, target, arguments.get(0).name(), featureMethod);
    }

    private List<Param> params(Method method, Class<? extends Annotation> kind, List<String> errors) {
        boolean completion = kind == CompletePrompt.class || kind == CompleteResourceTemplate.class;
        List<Param> params = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Parameter[] parameters = method.getParameters();
        Type[] types = method.getGenericParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> type = parameter.getType();
            if (type == McpRequest.class) {
                params.add(Param.Injected.MCP_REQUEST);
            } else if (type == Progress.class) {
                params.add(Param.Injected.PROGRESS);
            } else if (type == Cancellation.class) {
                params.add(Param.Injected.CANCELLATION);
            } else if (type == McpCaller.class) {
                params.add(Param.Injected.CALLER);
            } else if (type == Principal.class) {
                params.add(Param.Injected.PRINCIPAL);
            } else if (type == CompletionContext.class) {
                if (!completion) {
                    errors.add("CompletionContext is only available to Completion Methods");
                }
                params.add(Param.Injected.COMPLETION_CONTEXT);
            } else {
                Param.Argument argument = argument(parameter, types.length == parameters.length ? types[i] : type,
                        kind, i, errors);
                if (argument != null) {
                    if (!names.add(argument.name())) {
                        errors.add("more than one parameter has the Argument name '" + argument.name() + "'");
                    }
                    params.add(argument);
                }
            }
        }
        return params;
    }

    private Param.Argument argument(Parameter parameter, Type type, Class<? extends Annotation> kind, int index,
            List<String> errors) {
        String annotatedName = null;
        String title = null;
        String description = null;
        String defaultValue = null;
        boolean required = true;
        Class<? extends Annotation> argAnnotation;
        if (kind == Tool.class) {
            argAnnotation = ToolArg.class;
            ToolArg arg = parameter.getAnnotation(ToolArg.class);
            if (arg != null) {
                annotatedName = elementName(arg.name(), ToolArg.ELEMENT_NAME);
                description = emptyToNull(arg.description());
                defaultValue = emptyToNull(arg.defaultValue());
                required = arg.required();
            }
        } else if (kind == Prompt.class) {
            argAnnotation = PromptArg.class;
            PromptArg arg = parameter.getAnnotation(PromptArg.class);
            if (arg != null) {
                annotatedName = elementName(arg.name(), PromptArg.ELEMENT_NAME);
                title = emptyToNull(arg.title());
                description = emptyToNull(arg.description());
                defaultValue = emptyToNull(arg.defaultValue());
                required = arg.required();
            }
        } else if (kind == ResourceTemplate.class) {
            argAnnotation = ResourceTemplateArg.class;
            ResourceTemplateArg arg = parameter.getAnnotation(ResourceTemplateArg.class);
            annotatedName = arg == null ? null : elementName(arg.name(), ResourceTemplateArg.ELEMENT_NAME);
        } else if (kind == Resource.class) {
            argAnnotation = null;
        } else {
            argAnnotation = CompleteArg.class;
            CompleteArg arg = parameter.getAnnotation(CompleteArg.class);
            annotatedName = arg == null ? null : elementName(arg.name(), CompleteArg.ELEMENT_NAME);
        }
        String name = annotatedName != null ? annotatedName : parameter.isNamePresent() ? parameter.getName() : null;
        if (name == null) {
            errors.add("the Argument name of parameter " + index + " (" + parameter.getType().getSimpleName()
                    + ") can't be determined; " + (argAnnotation == null ? "compile with -parameters"
                            : "set @" + argAnnotation.getSimpleName() + "(name = ...) or compile with -parameters"));
            return null;
        }
        if (defaultValue != null) {
            try {
                mapping.binder().bindDefault(defaultValue, type);
            } catch (RuntimeException e) {
                errors.add("the defaultValue '" + defaultValue + "' of Argument '" + name + "' can't be bound to "
                        + type.getTypeName() + ": " + e.getMessage());
            }
        } else if (!required && Types.rawType(type).isPrimitive()) {
            errors.add("the Argument '" + name + "' has the primitive type " + type.getTypeName()
                    + " and required = false, so it needs a defaultValue or a wrapper type");
        }
        required = required && defaultValue == null && !mapping.binder().isOptionalType(type);
        return new Param.Argument(name, type, required, defaultValue, title, description);
    }

    private void finish(JsonObjectBuilder json, Method method, FeatureType type, String name) {
        Icons iconsAnnotation = method.getAnnotation(Icons.class);
        if (iconsAnnotation == null) {
            iconsAnnotation = method.getDeclaringClass().getAnnotation(Icons.class);
        }
        if (iconsAnnotation != null) {
            List<Icon> list = icons.icons(iconsAnnotation.iconProvider(), type, name);
            if (!list.isEmpty()) {
                json.add("icons", ProtocolJson.icons(list));
            }
        }
        JsonObject meta = MetaFields.toJson(method.getAnnotationsByType(MetaField.class));
        if (meta != null) {
            json.add("_meta", meta);
        }
    }

    private static Set<String> servers(Method method) {
        Set<String> servers = new LinkedHashSet<>();
        for (McpServer server : method.getAnnotationsByType(McpServer.class)) {
            servers.add(server.value());
        }
        for (McpServer server : method.getDeclaringClass().getAnnotationsByType(McpServer.class)) {
            servers.add(server.value());
        }
        if (servers.isEmpty()) {
            servers.add(McpServer.DEFAULT);
        }
        return servers;
    }

    private static JsonObject toolAnnotations(Tool.Annotations annotations) {
        JsonObjectBuilder json = Json.object();
        if (!annotations.title().isEmpty()) {
            json.add("title", annotations.title());
        }
        if (annotations.readOnlyHint()) {
            json.add("readOnlyHint", true);
        }
        if (!annotations.destructiveHint()) {
            json.add("destructiveHint", false);
        }
        if (annotations.idempotentHint()) {
            json.add("idempotentHint", true);
        }
        if (!annotations.openWorldHint()) {
            json.add("openWorldHint", false);
        }
        JsonObject result = json.build();
        return result.isEmpty() ? null : result;
    }

    /**
     * The {@code annotations} of a Resource or Resource Template definition, or {@code null} when none is set. They
     * are built with the SPI's {@link Annotations} builder and encoded like the annotations of values Feature
     * Methods return, so both follow the same rules: {@code priority} is {@code -1} (unset) or between 0.0 and
     * 1.0, and {@code lastModified} is an ISO 8601 date-time with offset, such as {@code 2025-01-12T15:00:58Z}.
     */
    private static JsonObject resourceAnnotations(Resource.Annotations annotations) {
        Annotations.Builder builder = Annotations.builder();
        boolean set = false;
        if (annotations.audience().length > 0) {
            builder.setAudience(annotations.audience());
            set = true;
        }
        if (annotations.priority() != -1) {
            try {
                builder.setPriority(annotations.priority());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("annotations.priority must be -1 (unset) or between 0.0 and 1.0, "
                        + "not " + annotations.priority());
            }
            set = true;
        }
        if (!annotations.lastModified().isEmpty()) {
            try {
                builder.setLastModified(OffsetDateTime.parse(annotations.lastModified()).toInstant());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("annotations.lastModified '" + annotations.lastModified()
                        + "' isn't an ISO 8601 date-time with offset, such as 2025-01-12T15:00:58Z");
            }
            set = true;
        }
        return set ? ProtocolJson.annotations(builder.build()) : null;
    }

    private static JsonObjectBuilder describe(JsonObjectBuilder json, String title, String description) {
        if (!title.isEmpty()) {
            json.add("title", title);
        }
        if (!description.isEmpty()) {
            json.add("description", description);
        }
        return json;
    }

    private static String name(String name, String elementName, Method method) {
        return name.equals(elementName) || name.isEmpty() ? method.getName() : name;
    }

    private static String elementName(String name, String elementName) {
        return name.equals(elementName) || name.isEmpty() ? null : name;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * The generic return type, with a {@code CompletionStage<T>} unwrapped to {@code T}.
     */
    static Type valueType(Method method) {
        Type type = method.getGenericReturnType();
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            Type[] arguments = Types.typeArguments(type, CompletionStage.class);
            return arguments == null ? Object.class : arguments[0];
        }
        return type;
    }

    private static boolean isOneOf(Type type, Class<?>... classes) {
        Class<?> raw = Types.rawType(type);
        return raw != Object.class && Arrays.stream(classes).anyMatch(c -> c.isAssignableFrom(raw));
    }

    private static boolean isListOf(Type type, Class<?> element) {
        Type[] arguments = Types.typeArguments(type, List.class);
        if (arguments == null) {
            return false;
        }
        Class<?> actual = Types.rawType(arguments[0]);
        return actual != Object.class && element.isAssignableFrom(actual);
    }
}
