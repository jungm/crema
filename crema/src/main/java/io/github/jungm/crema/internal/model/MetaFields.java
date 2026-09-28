package io.github.jungm.crema.internal.model;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.mcpjava.server.MetaField;

import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Turns {@link MetaField @MetaField} annotations into a definition's {@code _meta} object.
 */
final class MetaFields {

    private static final String LABEL = "[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?";
    private static final Pattern PREFIX = Pattern.compile(LABEL + "(?:\\." + LABEL + ")*/");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");
    private static final Set<String> RESERVED_LABELS = Set.of("modelcontextprotocol", "mcp");

    private MetaFields() {
    }

    /**
     * The {@code _meta} object of the fields, or {@code null} when there are none.
     *
     * @throws IllegalArgumentException naming the offending field
     */
    static JsonObject toJson(MetaField[] fields) {
        if (fields.length == 0) {
            return null;
        }
        JsonObjectBuilder json = Json.object();
        Set<String> keys = new HashSet<>();
        for (MetaField field : fields) {
            String key = key(field);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("more than one @MetaField has the key '" + key + "'");
            }
            json.add(key, value(field));
        }
        return json.build();
    }

    private static String key(MetaField field) {
        String prefix = field.prefix();
        if (!prefix.isEmpty()) {
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalArgumentException("@MetaField prefix '" + prefix
                        + "' must be dot-separated labels followed by '/', e.g. 'example.com/'");
            }
            if (isReserved(prefix)) {
                throw new IllegalArgumentException("@MetaField prefix '" + prefix + "' is reserved for MCP");
            }
        }
        if (!NAME.matcher(field.name()).matches()) {
            throw new IllegalArgumentException("@MetaField name '" + field.name()
                    + "' must begin and end with a letter or digit and contain only letters, digits, '-', '_' "
                    + "and '.'");
        }
        return prefix + field.name();
    }

    /**
     * A prefix is reserved if {@code modelcontextprotocol} or {@code mcp} is its second label (as in
     * {@code io.modelcontextprotocol/}) or any label but its last (as in {@code tools.mcp.com/}).
     */
    static boolean isReserved(String prefix) {
        List<String> labels = Arrays.asList(prefix.substring(0, prefix.length() - 1).split("\\."));
        if (labels.size() >= 2 && RESERVED_LABELS.contains(labels.get(1))) {
            return true;
        }
        return labels.subList(0, labels.size() - 1).stream().anyMatch(RESERVED_LABELS::contains);
    }

    private static JsonValue value(MetaField field) {
        String value = field.value();
        String key = field.prefix() + field.name();
        switch (field.type()) {
            case INT:
                try {
                    return Json.PROVIDER.createValue(new BigInteger(value.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("@MetaField '" + key + "' has type INT but value '" + value
                            + "' isn't an integer");
                }
            case BOOLEAN:
                if (value.equals("true")) {
                    return JsonValue.TRUE;
                } else if (value.equals("false")) {
                    return JsonValue.FALSE;
                }
                throw new IllegalArgumentException("@MetaField '" + key + "' has type BOOLEAN but value '" + value
                        + "' is neither 'true' nor 'false'");
            case JSON:
                try {
                    return Json.parse(value);
                } catch (JsonException e) {
                    throw new IllegalArgumentException("@MetaField '" + key + "' has type JSON but value '" + value
                            + "' isn't valid JSON: " + e.getMessage());
                }
            default:
                return Json.PROVIDER.createValue(value);
        }
    }
}
