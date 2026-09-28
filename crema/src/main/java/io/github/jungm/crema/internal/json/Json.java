package io.github.jungm.crema.internal.json;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.json.JsonArray;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.spi.JsonProvider;

/**
 * The one JSON-P provider and builder factory Crema creates all JSON values with, and general JSON-P helpers.
 * {@link ProtocolJson} builds on it for the wire shapes of the API's value types.
 */
public final class Json {

    public static final JsonProvider PROVIDER = JsonProvider.provider();
    public static final JsonBuilderFactory FACTORY = PROVIDER.createBuilderFactory(Map.of());

    private Json() {
    }

    public static JsonObjectBuilder object() {
        return FACTORY.createObjectBuilder();
    }

    /**
     * Parses one JSON value.
     *
     * @throws jakarta.json.JsonException if the input isn't a single well-formed JSON value
     */
    public static JsonValue parse(byte[] json) {
        try (JsonReader reader = PROVIDER.createReader(new ByteArrayInputStream(json))) {
            return reader.readValue();
        }
    }

    public static JsonValue parse(String json) {
        try (JsonReader reader = PROVIDER.createReader(new StringReader(json))) {
            return reader.readValue();
        }
    }

    /**
     * Writes compact single-line JSON.
     */
    public static String write(JsonValue value) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = PROVIDER.createWriter(out)) {
            writer.write(value);
        }
        return out.toString();
    }

    public static Optional<String> string(JsonObject json, String key) {
        return json != null && json.get(key) instanceof JsonString s ? Optional.of(s.getString()) : Optional.empty();
    }

    public static Optional<JsonObject> object(JsonObject json, String key) {
        return json != null && json.get(key) instanceof JsonObject o ? Optional.of(o) : Optional.empty();
    }

    /**
     * Converts JSON to plain Java values: {@code String}, {@code Boolean}, {@code Long} for integers that fit,
     * else {@code BigDecimal}, {@code List}, {@code Map} and {@code null}.
     */
    public static Object toJava(JsonValue value) {
        if (value == null) {
            return null;
        }
        switch (value.getValueType()) {
            case STRING:
                return ((JsonString) value).getString();
            case NUMBER:
                BigDecimal number = ((JsonNumber) value).bigDecimalValue();
                try {
                    return number.longValueExact();
                } catch (ArithmeticException e) {
                    return number;
                }
            case TRUE:
                return Boolean.TRUE;
            case FALSE:
                return Boolean.FALSE;
            case ARRAY:
                List<Object> list = new ArrayList<>();
                ((JsonArray) value).forEach(item -> list.add(toJava(item)));
                return list;
            case OBJECT:
                return toJavaMap((JsonObject) value);
            default:
                return null;
        }
    }

    public static Map<String, Object> toJavaMap(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        object.forEach((key, item) -> map.put(key, toJava(item)));
        return map;
    }
}
