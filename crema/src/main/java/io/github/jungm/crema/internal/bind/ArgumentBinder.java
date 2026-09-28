package io.github.jungm.crema.internal.bind;

import java.io.StringReader;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.stream.JsonParser;

/**
 * Binds Argument values (JSON) to Feature Method parameters (Java).
 * <p>
 * Scalars, {@code Optional*}, arrays and the standard collection and map types are bound here, so that errors carry
 * a path and a message a language model can act on. Everything else (application classes, records, unusual
 * collection types) is deserialized by {@link JsonbBridge}. Scalars are bound leniently: a number or boolean
 * written as a JSON string is accepted where a number or boolean is expected, and vice versa for strings. Thread-safe.
 */
public final class ArgumentBinder {

    private static final int MAX_INTEGER_DIGITS = 10_000;
    private static final int MAX_DESCRIBED_LENGTH = 60;

    private static final Map<Class<?>, Supplier<Collection<Object>>> COLLECTIONS = Map.ofEntries(
            Map.entry(Iterable.class, ArrayList::new),
            Map.entry(Collection.class, ArrayList::new),
            Map.entry(List.class, ArrayList::new),
            Map.entry(ArrayList.class, ArrayList::new),
            Map.entry(LinkedList.class, LinkedList::new),
            Map.entry(Set.class, LinkedHashSet::new),
            Map.entry(HashSet.class, HashSet::new),
            Map.entry(LinkedHashSet.class, LinkedHashSet::new),
            Map.entry(SortedSet.class, TreeSet::new),
            Map.entry(NavigableSet.class, TreeSet::new),
            Map.entry(TreeSet.class, TreeSet::new),
            Map.entry(Queue.class, ArrayDeque::new),
            Map.entry(Deque.class, ArrayDeque::new),
            Map.entry(ArrayDeque.class, ArrayDeque::new));

    private static final Map<Class<?>, Supplier<Map<String, Object>>> MAPS = Map.of(
            Map.class, LinkedHashMap::new,
            HashMap.class, HashMap::new,
            LinkedHashMap.class, LinkedHashMap::new,
            SortedMap.class, TreeMap::new,
            NavigableMap.class, TreeMap::new,
            TreeMap.class, TreeMap::new);

    private final JsonbBridge jsonb;

    public ArgumentBinder(JsonbBridge jsonb) {
        this.jsonb = jsonb;
    }

    /**
     * Binds a JSON value to an instance of {@code type}. A Java {@code null} is treated as JSON {@code null}.
     *
     * @throws BindingException if the value can't be bound
     */
    public Object bind(JsonValue value, Type type) {
        JsonValue json = value == null ? JsonValue.NULL : value;
        Type resolved = Types.resolve(type);
        Class<?> raw = Types.rawType(resolved);

        if (Types.isOptional(raw)) {
            return bindOptional(json, resolved, raw);
        }
        if (json.getValueType() == JsonValue.ValueType.NULL) {
            if (raw.isPrimitive()) {
                throw mismatch(expectation(raw), json);
            }
            return raw == JsonValue.class ? JsonValue.NULL : null;
        }
        if (JsonValue.class.isAssignableFrom(raw)) {
            return bindJsonValue(json, raw);
        }
        if (raw == Object.class) {
            return jsonb.fromJsonValue(json, Object.class);
        }
        Object scalar = bindScalar(json, raw);
        if (scalar != null) {
            return scalar;
        }
        Type component = Types.arrayComponentType(resolved);
        if (component != null) {
            return bindArray(json, component);
        }
        Supplier<Collection<Object>> collection = COLLECTIONS.get(raw);
        if (collection != null) {
            return bindCollection(json, resolved, collection.get());
        }
        Supplier<Map<String, Object>> map = MAPS.get(raw);
        if (map != null) {
            Type[] arguments = Types.typeArguments(resolved, Map.class);
            if (arguments[0] == String.class || arguments[0] == Object.class) {
                return bindMap(json, arguments[1], map.get());
            }
        }
        return bindWithJsonb(json, resolved, raw);
    }

    /**
     * Binds the {@code defaultValue} of an Argument. The string is parsed as a JSON literal; for {@code String},
     * {@code char}/{@code Character} and enum parameters (also wrapped in {@code Optional}) it's the string itself
     * unless it is a JSON string literal.
     *
     * @throws BindingException if the default value can't be bound
     */
    public Object bindDefault(String defaultValue, Type type) {
        Type resolved = Types.resolve(type);
        Type valueType = Types.isOptional(resolved) ? Types.optionalValueType(resolved) : resolved;
        JsonValue parsed = parseLiteral(defaultValue);
        JsonValue json;
        if (isStringLike(Types.rawType(valueType))) {
            json = parsed instanceof JsonString ? parsed : jsonb.jsonProvider().createValue(defaultValue);
        } else if (parsed == null) {
            throw new BindingException("invalid default value " + quote(defaultValue) + ": not a JSON value");
        } else {
            json = parsed;
        }
        try {
            return bind(json, resolved);
        } catch (BindingException e) {
            throw new BindingException("invalid default value " + quote(defaultValue) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the value of an absent Argument: {@code null}, or an empty {@code Optional*} for those types.
     *
     * @throws BindingException if {@code type} is primitive, since a primitive can't be absent
     */
    public Object absent(Type type) {
        Class<?> raw = Types.rawType(type);
        if (raw.isPrimitive()) {
            throw new BindingException("a value is required");
        }
        if (raw == Optional.class) {
            return Optional.empty();
        }
        if (raw == OptionalInt.class) {
            return OptionalInt.empty();
        }
        if (raw == OptionalLong.class) {
            return OptionalLong.empty();
        }
        if (raw == OptionalDouble.class) {
            return OptionalDouble.empty();
        }
        return null;
    }

    /**
     * Returns whether {@code type} is {@code Optional}, {@code OptionalInt}, {@code OptionalLong} or
     * {@code OptionalDouble}.
     */
    public boolean isOptionalType(Type type) {
        return Types.isOptional(type);
    }

    /**
     * Returns whether JSON {@code null} is a value of {@code type} rather than the absence of one: for
     * {@code Optional*} types (an empty optional), {@code Object} and {@code JsonValue}.
     */
    public boolean acceptsNull(Type type) {
        Class<?> raw = Types.rawType(Types.resolve(type));
        return Types.isOptional(raw) || raw == Object.class || raw == JsonValue.class;
    }

    private Object bindOptional(JsonValue json, Type resolved, Class<?> raw) {
        boolean isNull = json.getValueType() == JsonValue.ValueType.NULL;
        if (raw == OptionalInt.class) {
            return isNull ? OptionalInt.empty() : OptionalInt.of((Integer) bind(json, int.class));
        }
        if (raw == OptionalLong.class) {
            return isNull ? OptionalLong.empty() : OptionalLong.of((Long) bind(json, long.class));
        }
        if (raw == OptionalDouble.class) {
            return isNull ? OptionalDouble.empty() : OptionalDouble.of((Double) bind(json, double.class));
        }
        return isNull ? Optional.empty() : Optional.ofNullable(bind(json, Types.optionalValueType(resolved)));
    }

    private static Object bindJsonValue(JsonValue json, Class<?> raw) {
        if (raw.isInstance(json)) {
            return json;
        }
        String expected;
        if (raw == JsonObject.class) {
            expected = "an object";
        } else if (raw == JsonArray.class) {
            expected = "an array";
        } else if (raw == JsonStructure.class) {
            expected = "an object or an array";
        } else if (raw == JsonString.class) {
            expected = "a string";
        } else if (raw == JsonNumber.class) {
            expected = "a number";
        } else {
            expected = "a JSON value";
        }
        throw mismatch(expected, json);
    }

    /**
     * Returns the bound value, or {@code null} if {@code raw} is not a scalar type.
     */
    private static Object bindScalar(JsonValue json, Class<?> raw) {
        if (raw == String.class || raw == CharSequence.class) {
            return bindString(json);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return bindBoolean(json);
        }
        if (raw == char.class || raw == Character.class) {
            String string = json instanceof JsonString ? ((JsonString) json).getString() : null;
            if (string == null || string.length() != 1) {
                throw mismatch("a single character", json);
            }
            return string.charAt(0);
        }
        if (raw.isEnum()) {
            return bindEnum(json, raw);
        }
        if (raw == int.class || raw == Integer.class) {
            return bindInteger(json, Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
        }
        if (raw == long.class || raw == Long.class) {
            return bindInteger(json, Long.MIN_VALUE, Long.MAX_VALUE).longValue();
        }
        if (raw == short.class || raw == Short.class) {
            return bindInteger(json, Short.MIN_VALUE, Short.MAX_VALUE).shortValue();
        }
        if (raw == byte.class || raw == Byte.class) {
            return bindInteger(json, Byte.MIN_VALUE, Byte.MAX_VALUE).byteValue();
        }
        if (raw == BigInteger.class) {
            return bindBigInteger(json);
        }
        if (raw == double.class || raw == Double.class) {
            double value = bindNumber(json).doubleValue();
            if (Double.isInfinite(value)) {
                throw mismatch("a number within the range of a double", json);
            }
            return value;
        }
        if (raw == float.class || raw == Float.class) {
            float value = bindNumber(json).floatValue();
            if (Float.isInfinite(value)) {
                throw mismatch("a number within the range of a float", json);
            }
            return value;
        }
        if (raw == BigDecimal.class || raw == Number.class) {
            return bindNumber(json);
        }
        return null;
    }

    private static String bindString(JsonValue json) {
        switch (json.getValueType()) {
            case STRING:
                return ((JsonString) json).getString();
            case NUMBER:
            case TRUE:
            case FALSE:
                return json.toString();
            default:
                throw mismatch("a string", json);
        }
    }

    private static Boolean bindBoolean(JsonValue json) {
        if (json.getValueType() == JsonValue.ValueType.TRUE) {
            return Boolean.TRUE;
        }
        if (json.getValueType() == JsonValue.ValueType.FALSE) {
            return Boolean.FALSE;
        }
        if (json instanceof JsonString) {
            String string = ((JsonString) json).getString();
            if ("true".equalsIgnoreCase(string)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(string)) {
                return Boolean.FALSE;
            }
        }
        throw mismatch("a boolean", json);
    }

    private static Object bindEnum(JsonValue json, Class<?> raw) {
        Object[] constants = raw.getEnumConstants();
        if (json instanceof JsonString) {
            String name = ((JsonString) json).getString();
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
        }
        StringBuilder expected = new StringBuilder("one of ");
        for (int i = 0; i < constants.length; i++) {
            expected.append(i == 0 ? "" : ", ").append('"').append(((Enum<?>) constants[i]).name()).append('"');
        }
        throw mismatch(expected.toString(), json);
    }

    private static BigDecimal bindNumber(JsonValue json) {
        if (json instanceof JsonNumber) {
            return ((JsonNumber) json).bigDecimalValue();
        }
        if (json instanceof JsonString) {
            try {
                return new BigDecimal(((JsonString) json).getString());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw mismatch("a number", json);
    }

    private static BigInteger bindBigInteger(JsonValue json) {
        BigDecimal number = integralNumber(json);
        if (number.precision() - number.scale() > MAX_INTEGER_DIGITS) {
            throw mismatch("an integer with at most " + MAX_INTEGER_DIGITS + " digits", json);
        }
        return number.toBigIntegerExact();
    }

    private static BigDecimal bindInteger(JsonValue json, long min, long max) {
        BigDecimal number = integralNumber(json);
        // precision - scale is the number of integer digits; checking it first avoids expanding huge exponents.
        if (number.precision() - number.scale() > 19
                || number.compareTo(BigDecimal.valueOf(min)) < 0
                || number.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw mismatch("an integer between " + min + " and " + max, json);
        }
        return number;
    }

    private static BigDecimal integralNumber(JsonValue json) {
        BigDecimal number;
        try {
            number = bindNumber(json);
        } catch (BindingException e) {
            throw mismatch("an integer", json);
        }
        if (number.signum() != 0 && number.scale() > 0 && number.stripTrailingZeros().scale() > 0) {
            throw mismatch("an integer", json);
        }
        return number;
    }

    private Object bindArray(JsonValue json, Type componentType) {
        JsonArray array = requireArray(json);
        Class<?> componentClass = Types.rawType(componentType);
        Object result = Array.newInstance(componentClass, array.size());
        for (int i = 0; i < array.size(); i++) {
            try {
                Array.set(result, i, bind(array.get(i), componentType));
            } catch (BindingException e) {
                throw e.atIndex(i);
            }
        }
        return result;
    }

    private Object bindCollection(JsonValue json, Type resolved, Collection<Object> result) {
        JsonArray array = requireArray(json);
        Type[] arguments = Types.typeArguments(resolved, Iterable.class);
        Type elementType = arguments == null ? Object.class : arguments[0];
        for (int i = 0; i < array.size(); i++) {
            Object element;
            try {
                element = bind(array.get(i), elementType);
            } catch (BindingException e) {
                throw e.atIndex(i);
            }
            try {
                result.add(element);
            } catch (RuntimeException e) {
                // e.g. null in an ArrayDeque, or elements a TreeSet can't compare
                throw new BindingException("element can't be added: " + describe(array.get(i)), e).atIndex(i);
            }
        }
        return result;
    }

    private Object bindMap(JsonValue json, Type valueType, Map<String, Object> result) {
        if (!(json instanceof JsonObject)) {
            throw mismatch("an object", json);
        }
        for (Map.Entry<String, JsonValue> member : ((JsonObject) json).entrySet()) {
            try {
                result.put(member.getKey(), bind(member.getValue(), valueType));
            } catch (BindingException e) {
                throw e.atMember(member.getKey());
            }
        }
        return result;
    }

    private Object bindWithJsonb(JsonValue json, Type resolved, Class<?> raw) {
        if (!(json instanceof JsonObject) && expectsObject(raw)) {
            throw mismatch("an object", json);
        }
        try {
            return jsonb.fromJsonValue(json, resolved);
        } catch (JsonbException | JsonException e) {
            throw new BindingException("invalid value: " + e.getMessage(), e);
        }
    }

    private static boolean expectsObject(Class<?> raw) {
        if (Map.class.isAssignableFrom(raw)) {
            return true;
        }
        if (raw.isInterface() || raw.isPrimitive() || raw.isArray() || Collection.class.isAssignableFrom(raw)) {
            return false;
        }
        String name = raw.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")) {
            return false;
        }
        return !raw.isAnnotationPresent(JsonbTypeAdapter.class) && !raw.isAnnotationPresent(JsonbTypeDeserializer.class);
    }

    private static JsonArray requireArray(JsonValue json) {
        if (!(json instanceof JsonArray)) {
            throw mismatch("an array", json);
        }
        return (JsonArray) json;
    }

    private JsonValue parseLiteral(String literal) {
        try (JsonParser parser = jsonb.jsonProvider().createParser(new StringReader(literal))) {
            if (!parser.hasNext()) {
                return null;
            }
            parser.next();
            JsonValue value = parser.getValue();
            return parser.hasNext() ? null : value;
        } catch (RuntimeException e) {
            // JsonParsingException, or a parser state exception for malformed input
            return null;
        }
    }

    private static boolean isStringLike(Class<?> raw) {
        return raw == String.class || raw == CharSequence.class || raw == char.class || raw == Character.class
                || raw.isEnum();
    }

    private static String expectation(Class<?> raw) {
        if (raw == boolean.class) {
            return "a boolean";
        }
        if (raw == char.class) {
            return "a single character";
        }
        if (raw == float.class || raw == double.class) {
            return "a number";
        }
        return "an integer";
    }

    private static BindingException mismatch(String expected, JsonValue actual) {
        return new BindingException("expected " + expected + " but got " + describe(actual));
    }

    static String describe(JsonValue value) {
        switch (value.getValueType()) {
            case OBJECT:
                return "an object";
            case ARRAY:
                return "an array";
            case STRING:
                return quote(((JsonString) value).getString());
            default:
                String text = value.toString();
                return text.length() > MAX_DESCRIBED_LENGTH ? text.substring(0, MAX_DESCRIBED_LENGTH) + "…" : text;
        }
    }

    private static String quote(String string) {
        String shown = string.length() > MAX_DESCRIBED_LENGTH
                ? string.substring(0, MAX_DESCRIBED_LENGTH) + "…" : string;
        return '"' + shown.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
