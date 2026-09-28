package io.github.jungm.crema.internal.schema;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * A JSON Schema 2020-12 validator for exactly the keywords {@link SchemaGenerator} emits. Formats are asserted.
 * Unknown keywords fail validation, so the generator can't emit something this validator silently ignores.
 */
final class SchemaValidator {

    private static final Set<String> KEYWORDS = Set.of(
            "$ref", "$defs", "anyOf", "type", "enum", "const", "properties", "required", "additionalProperties",
            "items", "minLength", "maxLength", "format", "minimum", "maximum", "description");

    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile(
            "^(\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$");
    // RFC 3339, Appendix A
    private static final Pattern DURATION = Pattern.compile(
            "^P(?!$)(\\d+W|(\\d+Y)?(\\d+M)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+S)?)?)$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JsonObject root;
    private final List<String> errors = new ArrayList<>();

    private SchemaValidator(JsonObject root) {
        this.root = root;
    }

    static List<String> validate(JsonObject schema, JsonValue instance) {
        SchemaValidator validator = new SchemaValidator(schema);
        validator.validate(schema, instance, "$");
        return validator.errors;
    }

    private boolean isValid(JsonObject schema, JsonValue instance) {
        SchemaValidator nested = new SchemaValidator(root);
        nested.validate(schema, instance, "$");
        return nested.errors.isEmpty();
    }

    private void validate(JsonObject schema, JsonValue instance, String path) {
        for (String keyword : schema.keySet()) {
            if (!KEYWORDS.contains(keyword)) {
                errors.add(path + ": unsupported keyword " + keyword);
            }
        }
        if (schema.containsKey("$ref")) {
            validate(resolve(schema.getString("$ref")), instance, path);
        }
        if (schema.containsKey("anyOf")) {
            boolean any = false;
            for (JsonValue option : schema.getJsonArray("anyOf")) {
                any |= isValid(option.asJsonObject(), instance);
            }
            if (!any) {
                errors.add(path + ": matches no anyOf option: " + instance);
            }
        }
        if (schema.containsKey("type")) {
            JsonValue type = schema.get("type");
            List<String> types = new ArrayList<>();
            if (type instanceof JsonString) {
                types.add(((JsonString) type).getString());
            } else {
                for (JsonValue t : (JsonArray) type) {
                    types.add(((JsonString) t).getString());
                }
            }
            if (types.stream().noneMatch(t -> hasType(instance, t))) {
                errors.add(path + ": expected " + types + " but got " + instance);
                return;
            }
        }
        if (schema.containsKey("enum")) {
            if (schema.getJsonArray("enum").stream().noneMatch(v -> jsonEquals(v, instance))) {
                errors.add(path + ": not in enum: " + instance);
            }
        }
        if (schema.containsKey("const") && !jsonEquals(schema.get("const"), instance)) {
            errors.add(path + ": not const: " + instance);
        }
        if (instance instanceof JsonObject) {
            validateObject(schema, (JsonObject) instance, path);
        }
        if (instance instanceof JsonArray && schema.containsKey("items")) {
            JsonArray array = (JsonArray) instance;
            for (int i = 0; i < array.size(); i++) {
                validate(schema.getJsonObject("items"), array.get(i), path + "[" + i + "]");
            }
        }
        if (instance instanceof JsonString) {
            validateString(schema, ((JsonString) instance).getString(), path);
        }
        if (instance instanceof JsonNumber) {
            BigDecimal number = ((JsonNumber) instance).bigDecimalValue();
            if (schema.containsKey("minimum")
                    && number.compareTo(schema.getJsonNumber("minimum").bigDecimalValue()) < 0) {
                errors.add(path + ": below minimum: " + number);
            }
            if (schema.containsKey("maximum")
                    && number.compareTo(schema.getJsonNumber("maximum").bigDecimalValue()) > 0) {
                errors.add(path + ": above maximum: " + number);
            }
        }
    }

    private void validateObject(JsonObject schema, JsonObject object, String path) {
        JsonObject properties = schema.containsKey("properties")
                ? schema.getJsonObject("properties") : JsonValue.EMPTY_JSON_OBJECT;
        if (schema.containsKey("required")) {
            for (JsonValue name : schema.getJsonArray("required")) {
                if (!object.containsKey(((JsonString) name).getString())) {
                    errors.add(path + ": missing required property " + name);
                }
            }
        }
        for (Map.Entry<String, JsonValue> member : object.entrySet()) {
            String memberPath = path + "." + member.getKey();
            if (properties.containsKey(member.getKey())) {
                validate(properties.getJsonObject(member.getKey()), member.getValue(), memberPath);
            } else if (schema.containsKey("additionalProperties")) {
                JsonValue additional = schema.get("additionalProperties");
                if (additional == JsonValue.FALSE) {
                    errors.add(memberPath + ": additional property not allowed");
                } else if (additional instanceof JsonObject) {
                    validate((JsonObject) additional, member.getValue(), memberPath);
                }
            }
        }
    }

    private void validateString(JsonObject schema, String string, String path) {
        int length = string.codePointCount(0, string.length());
        if (schema.containsKey("minLength") && length < schema.getInt("minLength")) {
            errors.add(path + ": shorter than minLength: " + string);
        }
        if (schema.containsKey("maxLength") && length > schema.getInt("maxLength")) {
            errors.add(path + ": longer than maxLength: " + string);
        }
        if (schema.containsKey("format") && !hasFormat(string, schema.getString("format"))) {
            errors.add(path + ": not a valid " + schema.getString("format") + ": " + string);
        }
    }

    private static boolean hasFormat(String string, String format) {
        switch (format) {
            case "date-time":
                var dateTime = DATE_TIME.matcher(string);
                return dateTime.matches() && isDate(dateTime.group(1))
                        && isTime(dateTime.group(2), dateTime.group(3), dateTime.group(4));
            case "date":
                return DATE.matcher(string).matches() && isDate(string);
            case "time":
                var time = TIME.matcher(string);
                return time.matches() && isTime(time.group(1), time.group(2), time.group(3));
            case "duration":
                return DURATION.matcher(string).matches();
            case "uuid":
                return UUID.matcher(string).matches();
            case "uri":
                try {
                    return new URI(string).isAbsolute();
                } catch (URISyntaxException e) {
                    return false;
                }
            case "uri-reference":
                try {
                    new URI(string);
                    return true;
                } catch (URISyntaxException e) {
                    return false;
                }
            default:
                throw new IllegalArgumentException("unsupported format " + format);
        }
    }

    private static boolean isDate(String date) {
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isTime(String hours, String minutes, String seconds) {
        return Integer.parseInt(hours) < 24 && Integer.parseInt(minutes) < 60 && Integer.parseInt(seconds) < 61;
    }

    private JsonObject resolve(String reference) {
        if (reference.equals("#")) {
            return root;
        }
        String prefix = "#/$defs/";
        if (!reference.startsWith(prefix)) {
            throw new IllegalArgumentException("unsupported $ref " + reference);
        }
        JsonObject definition = root.getJsonObject("$defs").getJsonObject(reference.substring(prefix.length()));
        if (definition == null) {
            throw new IllegalArgumentException("dangling $ref " + reference);
        }
        return definition;
    }

    private static boolean hasType(JsonValue instance, String type) {
        switch (type) {
            case "null":
                return instance.getValueType() == JsonValue.ValueType.NULL;
            case "boolean":
                return instance == JsonValue.TRUE || instance == JsonValue.FALSE
                        || instance.getValueType() == JsonValue.ValueType.TRUE
                        || instance.getValueType() == JsonValue.ValueType.FALSE;
            case "object":
                return instance instanceof JsonObject;
            case "array":
                return instance instanceof JsonArray;
            case "string":
                return instance instanceof JsonString;
            case "number":
                return instance instanceof JsonNumber;
            case "integer":
                if (!(instance instanceof JsonNumber)) {
                    return false;
                }
                BigDecimal number = ((JsonNumber) instance).bigDecimalValue();
                return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
            default:
                throw new IllegalArgumentException("unsupported type " + type);
        }
    }

    private static boolean jsonEquals(JsonValue a, JsonValue b) {
        if (a instanceof JsonNumber && b instanceof JsonNumber) {
            return ((JsonNumber) a).bigDecimalValue().compareTo(((JsonNumber) b).bigDecimalValue()) == 0;
        }
        return a.equals(b);
    }
}
