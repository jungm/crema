package io.github.jungm.crema.it.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates JSON-RPC messages that Crema sends against the official MCP {@code 2026-07-28} JSON Schema, choosing the
 * definition the way the conformance suite's {@code wire-schema-valid} check does:
 * <ul>
 * <li>a notification against the definition whose {@code method} is its method;
 * <li>an error response against the definition whose {@code error.code} is its code, else
 * {@code JSONRPCErrorResponse};
 * <li>a result response's {@code result} against {@code <Method>Result} of the request's method, and the whole
 * message against {@code JSONRPCResultResponse}.
 * </ul>
 * The schema is vendored as {@code src/test/resources/mcp/schema-2026-07-28.json}, taken from
 * https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/271ecc9accafdd9b83a3c869fa67c22953b2af80/schema/2026-07-28/schema.json
 * (the last commit touching it; SHA-256 {@code ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203}).
 */
public final class WireSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WireSchema INSTANCE = new WireSchema();

    private final ObjectNode root;
    private final Map<String, String> methodDefs = new HashMap<>();
    private final Map<Integer, String> errorDefs = new HashMap<>();
    private final Map<String, String> resultDefs = new HashMap<>();
    private final Map<String, JsonSchema> validators = new ConcurrentHashMap<>();
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private WireSchema() {
        try (InputStream in = WireSchema.class.getResourceAsStream("/mcp/schema-2026-07-28.json")) {
            root = (ObjectNode) MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode defs = root.get("$defs");
        defs.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode method = entry.getValue().path("properties").path("method").path("const");
            if (method.isTextual()) {
                methodDefs.put(method.asText(), name);
                if (name.endsWith("Request")) {
                    String result = name.substring(0, name.length() - "Request".length()) + "Result";
                    if (defs.has(result)) {
                        resultDefs.put(method.asText(), result);
                    }
                }
            }
            for (JsonNode part : entry.getValue().path("properties").path("error").path("allOf")) {
                JsonNode code = part.path("properties").path("code").path("const");
                if (code.isInt()) {
                    errorDefs.put(code.asInt(), name);
                }
            }
        });
    }

    public static WireSchema get() {
        return INSTANCE;
    }

    /**
     * The violations of one message the server sent.
     *
     * @param requestMethod the method of the request the message answers
     * @return an empty list if the message is valid
     */
    public List<String> violations(String json, String requestMethod) {
        JsonNode message;
        try {
            message = MAPPER.readTree(json);
        } catch (IOException e) {
            return List.of("not JSON: " + e.getMessage());
        }
        if (!message.isObject()) {
            return List.of("JSONRPCMessage: not a single JSON-RPC message");
        }
        if (message.path("method").isTextual()) {
            String method = message.get("method").asText();
            return validate(methodDefs.getOrDefault(method,
                    message.has("id") ? "JSONRPCRequest" : "JSONRPCNotification"), message);
        }
        if (message.has("error") && !message.get("error").isNull()) {
            JsonNode code = message.get("error").path("code");
            String def = code.isInt() && errorDefs.containsKey(code.asInt()) ? errorDefs.get(code.asInt())
                    : "JSONRPCErrorResponse";
            return validate(def, message);
        }
        if (message.has("result")) {
            List<String> violations = new ArrayList<>();
            String def = resultDefs.get(requestMethod);
            if (def != null) {
                validate(def, message.get("result")).forEach(v -> violations.add(v + " (result of " + requestMethod
                        + ")"));
            }
            violations.addAll(validate("JSONRPCResultResponse", message));
            return violations;
        }
        return List.of("JSONRPCMessage: not a valid JSON-RPC request, notification, or response");
    }

    private List<String> validate(String def, JsonNode instance) {
        JsonSchema schema = validators.computeIfAbsent(def, name -> {
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            wrapper.put("$ref", "#/$defs/" + name);
            wrapper.set("$defs", root.get("$defs"));
            return factory.getSchema(wrapper);
        });
        Set<ValidationMessage> messages = schema.validate(instance);
        return messages.stream().map(m -> def + ": " + m.getMessage()).sorted().collect(Collectors.toList());
    }
}
