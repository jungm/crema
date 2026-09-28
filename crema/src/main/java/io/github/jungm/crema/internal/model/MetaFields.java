package io.github.jungm.crema.internal.model;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import org.mcpjava.server.MetaField;

import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.json.MetaKeys;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Turns {@link MetaField @MetaField} annotations into a definition's {@code _meta} object.
 */
final class MetaFields {

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

    /**
     * The key of a field, checked with {@link MetaKeys}; unlike a key in general, it needs a non-empty name.
     */
    private static String key(MetaField field) {
        try {
            MetaKeys.checkPrefix(field.prefix());
            if (field.name().isEmpty()) {
                throw new IllegalArgumentException("name must not be empty");
            }
            MetaKeys.checkName(field.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("@MetaField " + e.getMessage());
        }
        return field.prefix() + field.name();
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
