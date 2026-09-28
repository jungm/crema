package io.github.jungm.crema.internal.bind;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;

import io.github.jungm.crema.internal.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

/**
 * Converts between application values and JSON-P values with Crema's own {@link Jsonb} instance (default
 * configuration, except that {@code BigDecimal} and {@code BigInteger} are always written as JSON numbers). Never uses the application's {@code Jsonb}.
 * <p>
 * Values whose JSON-B mapping is fixed by the specification (strings, booleans, boxed numbers,
 * {@code BigDecimal}/{@code BigInteger}, JSON-P values) are converted directly; everything else goes through
 * {@link Jsonb}. Thread-safe. The owner closes it when the application stops.
 */
public final class JsonbBridge implements AutoCloseable {

    private final Jsonb jsonb;

    public JsonbBridge() {
        this.jsonb = JsonbBuilder.create(new JsonbConfig()
                .withSerializers(new BigDecimalSerializer(), new BigIntegerSerializer())
                .setProperty(JOHNZON_BIG_DECIMAL_AS_STRING, false)
                .setProperty(JOHNZON_BIG_INTEGER_AS_STRING, false));
    }

    /**
     * Johnzon (TomEE) writes {@code BigDecimal} and {@code BigInteger} properties as JSON strings unless these
     * properties are {@code false}, and then ignores {@link BigDecimalSerializer}. Other providers ignore unknown
     * properties.
     */
    private static final String JOHNZON_BIG_DECIMAL_AS_STRING = "johnzon.use-bigdecimal-stringadapter";
    private static final String JOHNZON_BIG_INTEGER_AS_STRING = "johnzon.use-biginteger-stringadapter";

    /**
     * Writes a {@code BigDecimal} as a JSON number, as the JSON-B specification requires and the generated JSON
     * Schema says.
     */
    private static final class BigDecimalSerializer implements JsonbSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
            generator.write(value);
        }
    }

    /**
     * Writes a {@code BigInteger} as a JSON number; see {@link BigDecimalSerializer}.
     */
    private static final class BigIntegerSerializer implements JsonbSerializer<BigInteger> {
        @Override
        public void serialize(BigInteger value, JsonGenerator generator, SerializationContext context) {
            generator.write(value);
        }
    }

    /**
     * Serializes a value with JSON-B, using its runtime type. {@code null} becomes {@link JsonValue#NULL}.
     *
     * @throws JsonbException if the value can't be serialized
     */
    public JsonValue toJsonValue(Object value) {
        JsonValue direct = directJsonValue(value);
        return direct != null ? direct : parse(jsonb.toJson(value));
    }

    /**
     * Serializes a value with JSON-B to JSON text.
     *
     * @throws JsonbException if the value can't be serialized
     */
    public String toJson(Object value) {
        JsonValue direct = directJsonValue(value);
        if (value instanceof Double || value instanceof Float) {
            // JSON-B writes toString(), e.g. 1.0E300 and -0.0, which JsonNumber doesn't preserve
            return value.toString();
        }
        return direct != null ? direct.toString() : jsonb.toJson(value);
    }

    /**
     * Deserializes a JSON value into an instance of {@code type} with JSON-B. JSON {@code null} (or a Java
     * {@code null}) yields {@code null}, or an empty {@code Optional*} for those types.
     *
     * @throws JsonbException if the value can't be represented as {@code type}
     */
    public Object fromJsonValue(JsonValue value, Type type) {
        JsonValue json = value == null ? JsonValue.NULL : value;
        Class<?> raw = Types.rawType(type);
        if (json.getValueType() == JsonValue.ValueType.NULL) {
            return nullValue(raw);
        }
        if (JsonValue.class.isAssignableFrom(raw)) {
            if (raw.isInstance(json)) {
                return json;
            }
            throw new JsonbException("Cannot deserialize " + json.getValueType() + " into " + raw.getName());
        }
        Object direct = directJavaValue(json, raw);
        return direct != null ? direct : jsonb.fromJson(json.toString(), type);
    }

    private static Object nullValue(Class<?> raw) {
        if (raw.isPrimitive()) {
            throw new JsonbException("Cannot deserialize null into " + raw.getName());
        }
        return raw == JsonValue.class ? JsonValue.NULL : Types.emptyOptional(raw);
    }

    private JsonValue directJsonValue(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof JsonValue) {
            return (JsonValue) value;
        }
        Class<?> type = value.getClass();
        if (type == String.class) {
            return Json.PROVIDER.createValue((String) value);
        }
        if (type == Boolean.class) {
            return (Boolean) value ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (type == Integer.class || type == Short.class || type == Byte.class) {
            return Json.PROVIDER.createValue(((Number) value).intValue());
        }
        if (type == Long.class) {
            return Json.PROVIDER.createValue((long) value);
        }
        if (type == BigDecimal.class) {
            return Json.PROVIDER.createValue((BigDecimal) value);
        }
        if (type == BigInteger.class) {
            return Json.PROVIDER.createValue((BigInteger) value);
        }
        if (type == Double.class || type == Float.class) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new JsonbException("Cannot serialize " + value + ": JSON numbers can't be NaN or infinite");
            }
            // JSON-B mandates the toString() representation, which differs from a widened float's.
            return Json.PROVIDER.createValue(new BigDecimal(value.toString()));
        }
        if (type == Character.class) {
            return Json.PROVIDER.createValue(value.toString());
        }
        return null;
    }

    private static Object directJavaValue(JsonValue json, Class<?> raw) {
        switch (json.getValueType()) {
            case STRING:
                return raw == String.class || raw == Object.class ? ((JsonString) json).getString() : null;
            case TRUE:
                return raw == Boolean.class || raw == boolean.class || raw == Object.class ? Boolean.TRUE : null;
            case FALSE:
                return raw == Boolean.class || raw == boolean.class || raw == Object.class ? Boolean.FALSE : null;
            case NUMBER:
                return directNumber((JsonNumber) json, raw);
            default:
                return null;
        }
    }

    private static Object directNumber(JsonNumber number, Class<?> raw) {
        if (raw == BigDecimal.class || raw == Object.class || raw == Number.class) {
            return number.bigDecimalValue();
        }
        if (raw == Double.class || raw == double.class) {
            double value = number.doubleValue();
            return Double.isInfinite(value) ? null : value;
        }
        if (!number.isIntegral()) {
            return null;
        }
        try {
            if (raw == Integer.class || raw == int.class) {
                return number.intValueExact();
            }
            if (raw == Long.class || raw == long.class) {
                return number.longValueExact();
            }
        } catch (ArithmeticException e) {
            return null;
        }
        return null;
    }

    private JsonValue parse(String json) {
        try (JsonParser parser = Json.PROVIDER.createParser(new StringReader(json))) {
            parser.next();
            return parser.getValue();
        }
    }

    /**
     * Releases the resources of the {@link Jsonb} instance, such as its caches of application classes. The
     * bridge isn't usable afterwards.
     *
     * @throws JsonbException if closing fails
     */
    @Override
    public void close() {
        try {
            jsonb.close();
        } catch (Exception e) {
            throw new JsonbException("Closing Jsonb failed", e);
        }
    }
}
