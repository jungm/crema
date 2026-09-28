package io.github.jungm.crema.internal.schema;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;

import io.github.jungm.crema.internal.bind.Types;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.schema.BeanIntrospector.BeanProperty;
import io.github.jungm.crema.internal.schema.BeanIntrospector.Direction;
import io.github.jungm.crema.internal.schema.BeanIntrospector.Formats;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;

/**
 * Generates JSON Schema (draft 2020-12) for Tool {@code inputSchema} and {@code outputSchema}, mirroring the JSON-B
 * default mapping (JSON-B 3.0, chapters 3 and 4) that binds Arguments and serializes results.
 * <p>
 * Input schemas describe what JSON-B reads (setters, non-final public fields, {@code @JsonbCreator} and record
 * constructor parameters); output schemas describe what it writes (getters, public fields, record components).
 * Application classes referenced more than once, or recursively, go to {@code $defs} and are referenced with
 * {@code $ref}; the root class of an output schema is referenced as {@code #}. Properties that JSON-B always writes
 * (primitives, {@code @JsonbNillable} properties) are {@code required} in output schemas; no property of an
 * application class is required in input schemas, since JSON-B leaves absent properties untouched.
 * <p>
 * Schemas never forbid additional properties, so a runtime subtype of a declared type still validates. Elements of
 * arrays and values of maps are nullable only for {@code Optional} element types (JSON-B writes empty optionals
 * there as {@code null}). Types whose mapping JSON-B doesn't define (other JDK classes, maps with non-scalar keys,
 * properties with custom serializers) get the empty schema, which accepts anything. Thread-safe.
 */
public final class SchemaGenerator {

    private static final Set<Class<?>> NUMBER_TYPES = Set.of(
            byte.class, short.class, int.class, long.class, float.class, double.class,
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
            BigInteger.class, BigDecimal.class, Number.class);

    private static final Set<Class<?>> DATE_TYPES = Set.of(
            Date.class, Calendar.class, Instant.class, LocalDate.class, LocalTime.class, LocalDateTime.class,
            ZonedDateTime.class, OffsetDateTime.class, OffsetTime.class);

    /**
     * Returns the {@code inputSchema} of a Tool: an object with one property per Argument, in the given order.
     */
    public JsonObject inputSchema(List<SchemaProperty> properties) {
        Run run = new Run(Direction.DESERIALIZATION, null);
        for (SchemaProperty property : properties) {
            run.count(Types.resolve(property.type()));
        }
        run.nameDefinitions();

        JsonObjectBuilder schemas = Json.FACTORY.createObjectBuilder();
        JsonArrayBuilder required = Json.FACTORY.createArrayBuilder();
        boolean anyRequired = false;
        for (SchemaProperty property : properties) {
            JsonObject schema = run.schema(Types.resolve(property.type()), Formats.DEFAULT);
            if (property.description() != null && !property.description().isEmpty()) {
                schema = Json.FACTORY.createObjectBuilder(schema).add("description", property.description()).build();
            }
            schemas.add(property.name(), schema);
            if (property.required()) {
                required.add(property.name());
                anyRequired = true;
            }
        }
        JsonObjectBuilder root = Json.FACTORY.createObjectBuilder()
                .add("type", "object")
                .add("properties", schemas);
        if (anyRequired) {
            root.add("required", required);
        }
        run.addDefinitions(root);
        return root.build();
    }

    /**
     * Returns the schema of the JSON that JSON-B writes for values of {@code type}, for a Tool's
     * {@code outputSchema}. The root may describe any JSON value, such as an array or a string, since structured
     * content may be any JSON value; the schema itself is always a JSON object.
     */
    public JsonObject schemaFor(Type type) {
        Type resolved = Types.resolve(type);
        Run run = new Run(Direction.SERIALIZATION, resolved);
        run.count(resolved);
        run.nameDefinitions();
        JsonObject schema = run.schema(resolved, Formats.DEFAULT);
        if (Types.isOptional(resolved)) {
            // an empty optional is written as null
            schema = nullable(schema);
        }
        JsonObjectBuilder root = Json.FACTORY.createObjectBuilder(schema);
        run.addDefinitions(root);
        return root.build();
    }

    private enum Kind {
        ANY, SCALAR, ENUM, OPTIONAL, JSON_VALUE, ARRAY, COLLECTION, MAP, ADAPTED, CUSTOM, BEAN
    }

    private Kind kind(Type type, Direction direction) {
        Class<?> raw = Types.rawType(type);
        if (raw == Object.class) {
            return Kind.ANY;
        }
        if (raw.isPrimitive() || scalarSchema(raw, Formats.DEFAULT) != null) {
            return Kind.SCALAR;
        }
        if (Types.isOptional(raw)) {
            return Kind.OPTIONAL;
        }
        if (JsonValue.class.isAssignableFrom(raw)) {
            return Kind.JSON_VALUE;
        }
        if (raw.isArray()) {
            return Kind.ARRAY;
        }
        if (Collection.class.isAssignableFrom(raw) || raw == Iterable.class) {
            return Kind.COLLECTION;
        }
        if (Map.class.isAssignableFrom(raw)) {
            return Kind.MAP;
        }
        if (direction == Direction.SERIALIZATION
                ? raw.isAnnotationPresent(JsonbTypeSerializer.class)
                : raw.isAnnotationPresent(JsonbTypeDeserializer.class)) {
            return Kind.CUSTOM;
        }
        if (raw.isAnnotationPresent(JsonbTypeAdapter.class)) {
            return Kind.ADAPTED;
        }
        if (Enum.class.isAssignableFrom(raw)) {
            return Kind.ENUM;
        }
        if (Types.isPlatformClass(raw)) {
            return Kind.ANY;
        }
        return Kind.BEAN;
    }

    private static Type adaptedType(Class<?> raw) {
        Type[] arguments = Types.typeArguments(raw.getAnnotation(JsonbTypeAdapter.class).value(), JsonbAdapter.class);
        return arguments == null ? Object.class : arguments[1];
    }

    private static Type elementType(Type type) {
        Type component = Types.arrayComponentType(type);
        if (component != null) {
            return component;
        }
        Type[] arguments = Types.typeArguments(type, Iterable.class);
        return arguments == null ? Object.class : arguments[0];
    }

    /**
     * Returns the value type of a map JSON-B writes as a JSON object, or {@code null} if the key type isn't one
     * whose values map to JSON strings.
     */
    private Type mapValueType(Type type, Direction direction) {
        Type[] arguments = Types.typeArguments(type, Map.class);
        Type key = arguments[0];
        Kind keyKind = kind(key, direction);
        if (keyKind != Kind.SCALAR && keyKind != Kind.ENUM) {
            return null;
        }
        return arguments[1];
    }

    /**
     * Returns the schema of a JSON-B built-in scalar type, or {@code null} if {@code raw} isn't one.
     */
    private JsonObject scalarSchema(Class<?> raw, Formats formats) {
        if (formats.numberFormat() != null && NUMBER_TYPES.contains(raw)) {
            return type("string").build();
        }
        if (formats.dateFormat() != null && isDateType(raw)) {
            return JsonbDateFormat.TIME_IN_MILLIS.equals(formats.dateFormat())
                    ? type("integer").build() : type("string").build();
        }
        if (raw == String.class || raw == CharSequence.class) {
            return type("string").build();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return type("boolean").build();
        }
        if (raw == char.class || raw == Character.class) {
            return type("string").add("minLength", 1).add("maxLength", 1).build();
        }
        if (raw == int.class || raw == Integer.class) {
            return integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        if (raw == long.class || raw == Long.class || raw == BigInteger.class) {
            return type("integer").build();
        }
        if (raw == short.class || raw == Short.class) {
            return integer(Short.MIN_VALUE, Short.MAX_VALUE);
        }
        if (raw == byte.class || raw == Byte.class) {
            return integer(Byte.MIN_VALUE, Byte.MAX_VALUE);
        }
        if (raw == float.class || raw == double.class || raw == Float.class || raw == Double.class
                || raw == BigDecimal.class || raw == Number.class) {
            return type("number").build();
        }
        if (raw == Instant.class || raw == OffsetDateTime.class) {
            return format("date-time");
        }
        if (raw == LocalDate.class) {
            return format("date");
        }
        if (raw == OffsetTime.class) {
            return format("time");
        }
        if (raw == Period.class) {
            return format("duration");
        }
        if (raw == UUID.class) {
            return format("uuid");
        }
        if (raw == URI.class) {
            return format("uri-reference");
        }
        if (raw == URL.class) {
            return format("uri");
        }
        // Formats JSON Schema can't express: ISO_LOCAL_* lack an offset, ISO_ZONED_DATE_TIME appends [zone], and
        // Duration.toString() may have fractional or negative seconds.
        if (raw == LocalDateTime.class || raw == LocalTime.class || raw == ZonedDateTime.class
                || raw == Duration.class || ZoneId.class.isAssignableFrom(raw)
                || Date.class.isAssignableFrom(raw) || Calendar.class.isAssignableFrom(raw)
                || TimeZone.class.isAssignableFrom(raw)) {
            return type("string").build();
        }
        return null;
    }

    private static boolean isDateType(Class<?> raw) {
        return DATE_TYPES.contains(raw) || Date.class.isAssignableFrom(raw) || Calendar.class.isAssignableFrom(raw);
    }

    private JsonObjectBuilder type(String type) {
        return Json.FACTORY.createObjectBuilder().add("type", type);
    }

    private JsonObject integer(long minimum, long maximum) {
        return type("integer").add("minimum", minimum).add("maximum", maximum).build();
    }

    private JsonObject format(String format) {
        return type("string").add("format", format).build();
    }

    private JsonObject nullable(JsonObject schema) {
        if (schema.isEmpty()) {
            return schema;
        }
        JsonValue type = schema.get("type");
        if (!schema.containsKey("enum") && !schema.containsKey("const")) {
            if (type instanceof JsonString) {
                JsonArray types = Json.FACTORY.createArrayBuilder().add(type).add("null").build();
                return Json.FACTORY.createObjectBuilder(schema).add("type", types).build();
            }
            if (type instanceof JsonArray) {
                if (((JsonArray) type).contains(Json.PROVIDER.createValue("null"))) {
                    return schema;
                }
                JsonArray types = Json.FACTORY.createArrayBuilder((JsonArray) type).add("null").build();
                return Json.FACTORY.createObjectBuilder(schema).add("type", types).build();
            }
        }
        return Json.FACTORY.createObjectBuilder()
                .add("anyOf", Json.FACTORY.createArrayBuilder().add(schema).add(type("null")))
                .build();
    }

    /**
     * State of one schema generation: occurrence counts of application classes, their {@code $defs} names and the
     * definitions built so far.
     */
    private final class Run {

        private final Direction direction;
        private final Type root;
        private final BeanIntrospector introspector;
        private final Map<Type, List<BeanProperty>> properties = new HashMap<>();
        private final Map<Type, Integer> counts = new LinkedHashMap<>();
        private final Map<Type, String> names = new HashMap<>();
        private final Set<Type> started = new HashSet<>();
        private final Map<String, JsonObject> definitions = new TreeMap<>();

        Run(Direction direction, Type root) {
            this.direction = direction;
            this.root = root;
            this.introspector = new BeanIntrospector(direction);
        }

        private List<BeanProperty> properties(Type bean) {
            return properties.computeIfAbsent(bean, introspector::properties);
        }

        /**
         * Counts the occurrences of application classes reachable from {@code type}, descending into each class
         * once.
         */
        void count(Type type) {
            switch (kind(type, direction)) {
                case OPTIONAL:
                    count(Types.optionalValueType(type));
                    break;
                case ARRAY:
                case COLLECTION:
                    count(elementType(type));
                    break;
                case MAP:
                    Type valueType = mapValueType(type, direction);
                    if (valueType != null) {
                        count(valueType);
                    }
                    break;
                case ADAPTED:
                    count(adaptedType(Types.rawType(type)));
                    break;
                case BEAN:
                    if (counts.merge(type, 1, Integer::sum) == 1) {
                        for (BeanProperty property : properties(type)) {
                            if (property.customSerialization()) {
                                continue;
                            }
                            count(property.adaptedType() != null ? property.adaptedType() : property.type());
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        /**
         * Assigns {@code $defs} names to the application classes that occur more than once, in order of first
         * occurrence. The root of an output schema is referenced as {@code #} instead.
         */
        void nameDefinitions() {
            Set<String> used = new HashSet<>();
            for (Map.Entry<Type, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > 1 && !entry.getKey().equals(root)) {
                    String base = baseName(entry.getKey());
                    String name = base;
                    for (int i = 2; !used.add(name); i++) {
                        name = base + i;
                    }
                    names.put(entry.getKey(), name);
                }
            }
        }

        void addDefinitions(JsonObjectBuilder schema) {
            if (!definitions.isEmpty()) {
                JsonObjectBuilder defs = Json.FACTORY.createObjectBuilder();
                definitions.forEach(defs::add);
                schema.add("$defs", defs);
            }
        }

        JsonObject schema(Type type, Formats formats) {
            Class<?> raw = Types.rawType(type);
            switch (kind(type, direction)) {
                case SCALAR:
                    return scalarSchema(raw, formats);
                case ENUM:
                    return enumSchema(raw);
                case OPTIONAL:
                    return schema(Types.optionalValueType(type), formats);
                case JSON_VALUE:
                    return jsonValueSchema(raw);
                case ARRAY:
                case COLLECTION:
                    return type("array").add("items", element(elementType(type))).build();
                case MAP:
                    Type valueType = mapValueType(type, direction);
                    if (valueType == null) {
                        return JsonValue.EMPTY_JSON_OBJECT;
                    }
                    JsonObject values = element(valueType);
                    JsonObjectBuilder map = type("object");
                    if (!values.isEmpty()) {
                        map.add("additionalProperties", values);
                    }
                    return map.build();
                case ADAPTED:
                    return schema(adaptedType(raw), Formats.DEFAULT);
                case BEAN:
                    return beanReference(type);
                default:
                    return JsonValue.EMPTY_JSON_OBJECT;
            }
        }

        /**
         * Returns the schema of an array element or map value. Property-level formats don't apply to these.
         */
        private JsonObject element(Type elementType) {
            JsonObject schema = schema(elementType, Formats.DEFAULT);
            return Types.isOptional(elementType) ? nullable(schema) : schema;
        }

        private JsonObject enumSchema(Class<?> raw) {
            JsonObjectBuilder schema = type("string");
            // the class of a constant with a body is an anonymous subclass of the enum class
            Class<?> enumClass = raw.isEnum() ? raw : raw.getSuperclass();
            if (enumClass.isEnum()) {
                JsonArrayBuilder values = Json.FACTORY.createArrayBuilder();
                for (Object constant : enumClass.getEnumConstants()) {
                    values.add(((Enum<?>) constant).name());
                }
                schema.add("enum", values);
            }
            return schema.build();
        }

        private JsonObject jsonValueSchema(Class<?> raw) {
            if (raw == JsonObject.class) {
                return type("object").build();
            }
            if (raw == JsonArray.class) {
                return type("array").build();
            }
            if (raw == JsonString.class) {
                return type("string").build();
            }
            if (raw == JsonNumber.class) {
                return type("number").build();
            }
            if (raw == JsonStructure.class) {
                return Json.FACTORY.createObjectBuilder()
                        .add("type", Json.FACTORY.createArrayBuilder().add("object").add("array"))
                        .build();
            }
            return JsonValue.EMPTY_JSON_OBJECT;
        }

        private JsonObject beanReference(Type type) {
            if (type.equals(root) && counts.getOrDefault(type, 0) > 1) {
                if (started.contains(type)) {
                    return ref("#");
                }
                started.add(type);
                return beanSchema(type);
            }
            String name = names.get(type);
            if (name == null) {
                return beanSchema(type);
            }
            if (started.add(type)) {
                definitions.put(name, beanSchema(type));
            }
            return ref("#/$defs/" + name);
        }

        private JsonObject ref(String reference) {
            return Json.FACTORY.createObjectBuilder().add("$ref", reference).build();
        }

        /**
         * Returns whether JSON-B always writes a property: primitives, and {@code @JsonbNillable} properties
         * (JSON-B 3.0, section 4.3.1). Nillable properties of application classes don't count, since Yasson omits
         * them when {@code null} regardless.
         */
        private boolean isAlwaysWritten(BeanProperty property) {
            if (property.isPrimitive()) {
                return true;
            }
            if (!property.nillable()) {
                return false;
            }
            Type valueType = property.adaptedType() != null ? property.adaptedType() : property.type();
            if (Types.isOptional(valueType)) {
                valueType = Types.optionalValueType(valueType);
            }
            return property.customSerialization() || kind(valueType, direction) != Kind.BEAN;
        }

        private JsonObject beanSchema(Type type) {
            JsonObjectBuilder schemas = Json.FACTORY.createObjectBuilder();
            JsonArrayBuilder required = Json.FACTORY.createArrayBuilder();
            boolean anyProperty = false;
            boolean anyRequired = false;
            for (BeanProperty property : properties(type)) {
                JsonObject schema;
                if (property.customSerialization()) {
                    schema = JsonValue.EMPTY_JSON_OBJECT;
                } else if (property.adaptedType() != null) {
                    schema = schema(property.adaptedType(), Formats.DEFAULT);
                } else {
                    schema = schema(property.type(), property.formats());
                }
                if (property.nillable() && !Types.rawType(property.type()).isPrimitive()) {
                    schema = nullable(schema);
                }
                schemas.add(property.name(), schema);
                anyProperty = true;
                if (direction == Direction.SERIALIZATION && isAlwaysWritten(property)) {
                    required.add(property.name());
                    anyRequired = true;
                }
            }
            JsonObjectBuilder schema = type("object");
            if (anyProperty) {
                schema.add("properties", schemas);
            }
            if (anyRequired) {
                schema.add("required", required);
            }
            return schema.build();
        }
    }

    private static String baseName(Type type) {
        StringBuilder name = new StringBuilder();
        appendName(type, name);
        String sanitized = name.toString().replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.isEmpty() ? "Type" : sanitized;
    }

    private static void appendName(Type type, StringBuilder name) {
        Type component = Types.arrayComponentType(type);
        if (component != null) {
            appendName(component, name);
            name.append("Array");
            return;
        }
        Class<?> raw = Types.rawType(type);
        String simpleName = raw.getSimpleName();
        if (simpleName.isEmpty()) {
            String fullName = raw.getName();
            simpleName = fullName.substring(Math.max(fullName.lastIndexOf('.'), fullName.lastIndexOf('$')) + 1);
        }
        name.append(simpleName);
        if (type instanceof java.lang.reflect.ParameterizedType) {
            Type[] arguments = ((java.lang.reflect.ParameterizedType) type).getActualTypeArguments();
            List<String> parts = new ArrayList<>();
            for (Type argument : arguments) {
                StringBuilder part = new StringBuilder();
                appendName(argument, part);
                parts.add(part.toString());
            }
            name.append("Of").append(String.join("And", parts));
        }
    }
}
