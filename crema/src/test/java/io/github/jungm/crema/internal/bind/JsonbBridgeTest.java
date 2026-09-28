package io.github.jungm.crema.internal.bind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import jakarta.json.Json;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JsonbBridgeTest {

    private static Jsonb reference;
    private final JsonbBridge bridge = new JsonbBridge();

    public record Point(int x, int y) {
    }

    public enum Color { RED }

    @BeforeAll
    static void createReference() {
        reference = JsonbBuilder.create();
    }

    @AfterAll
    static void closeReference() throws Exception {
        reference.close();
    }

    @AfterEach
    void close() throws Exception {
        bridge.close();
    }

    static List<Object> values() {
        return List.of("text", "with \"quotes\"", true, false, 1, (short) -2, (byte) 3, Long.MAX_VALUE, 0.1f, 1.0,
                1e300, -0.0, new BigDecimal("1.50"), new BigInteger("123456789012345678901234567890"), 'c',
                Color.RED, new Point(1, 2), List.of(1, "a"), Map.of("k", List.of()), LocalDate.of(2024, 1, 1),
                Optional.of("x"), new int[] {1, 2});
    }

    @ParameterizedTest
    @MethodSource("values")
    void toJsonValueMatchesJsonb(Object value) {
        JsonValue expected = Json.createReader(new StringReader(reference.toJson(value))).readValue();

        assertEquals(expected, bridge.toJsonValue(value));
        assertEquals(reference.toJson(value), bridge.toJson(value));
    }

    public record Amount(BigDecimal value, BigInteger count, List<BigDecimal> parts) {
    }

    @Test
    void bigNumbersInsideObjectsAreJsonNumbers() {
        JsonValue expected = Json.createObjectBuilder().add("count", new BigInteger("12345678901234567890"))
                .add("parts", Json.createArrayBuilder().add(new BigDecimal("0.1")))
                .add("value", new BigDecimal("1.50")).build();

        assertEquals(expected, bridge.toJsonValue(new Amount(new BigDecimal("1.50"),
                new BigInteger("12345678901234567890"), List.of(new BigDecimal("0.1")))));
    }

    @Test
    void toJsonValueOfNullAndJsonValues() {
        assertSame(JsonValue.NULL, bridge.toJsonValue(null));
        JsonValue object = Json.createObjectBuilder().add("a", 1).build();
        assertSame(object, bridge.toJsonValue(object));
        assertEquals("null", bridge.toJson(null));
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        assertThrows(JsonbException.class, () -> bridge.toJsonValue(Double.NaN));
        assertThrows(JsonbException.class, () -> bridge.toJsonValue(Float.POSITIVE_INFINITY));
    }

    @Test
    void toJsonValueWithDeclaredType() {
        Type type = new TypeLiteral<List<Optional<String>>>() { }.type();
        assertEquals(Json.createArrayBuilder().add("a").addNull().build(),
                bridge.toJsonValue(java.util.Arrays.asList(Optional.of("a"), Optional.empty()), type));
    }

    @Test
    void fromJsonValue() {
        assertEquals("a", bridge.fromJsonValue(Json.createValue("a"), String.class));
        assertEquals(5, bridge.fromJsonValue(Json.createValue(5), int.class));
        assertEquals(5L, bridge.fromJsonValue(Json.createValue(5), Long.class));
        assertEquals(2.5, bridge.fromJsonValue(Json.createValue(2.5), double.class));
        assertEquals(new BigDecimal("2.50"), bridge.fromJsonValue(Json.createValue(new BigDecimal("2.50")),
                BigDecimal.class));
        assertEquals(new BigDecimal("7"), bridge.fromJsonValue(Json.createValue(7), Object.class));
        assertEquals(Boolean.TRUE, bridge.fromJsonValue(JsonValue.TRUE, boolean.class));
        assertEquals(new Point(1, 2), bridge.fromJsonValue(
                Json.createObjectBuilder().add("x", 1).add("y", 2).build(), Point.class));
        assertEquals(List.of(1, 2), bridge.fromJsonValue(Json.createArrayBuilder().add(1).add(2).build(),
                new TypeLiteral<List<Integer>>() { }.type()));
        assertEquals(Map.of("a", new BigDecimal("1")), bridge.fromJsonValue(
                Json.createObjectBuilder().add("a", 1).build(), Object.class));
        assertEquals(Color.RED, bridge.fromJsonValue(Json.createValue("RED"), Color.class));
        // not a direct conversion: JSON-B's own rules apply
        assertEquals(5, bridge.fromJsonValue(Json.createValue(new BigDecimal("5.0")), Integer.class));
    }

    @Test
    void fromJsonValueOfNull() {
        assertNull(bridge.fromJsonValue(JsonValue.NULL, String.class));
        assertNull(bridge.fromJsonValue(null, Point.class));
        assertEquals(Optional.empty(), bridge.fromJsonValue(JsonValue.NULL, Optional.class));
        assertEquals(OptionalInt.empty(), bridge.fromJsonValue(JsonValue.NULL, OptionalInt.class));
        assertSame(JsonValue.NULL, bridge.fromJsonValue(JsonValue.NULL, JsonValue.class));
        assertThrows(JsonbException.class, () -> bridge.fromJsonValue(JsonValue.NULL, int.class));
    }

    @Test
    void fromJsonValueToJsonValueTypes() {
        JsonValue array = Json.createArrayBuilder().add(1).build();
        assertSame(array, bridge.fromJsonValue(array, JsonValue.class));
        assertSame(array, bridge.fromJsonValue(array, jakarta.json.JsonArray.class));
        assertThrows(JsonbException.class, () -> bridge.fromJsonValue(array, jakarta.json.JsonObject.class));
    }

    @Test
    void fromJsonValueReportsInvalidValues() {
        assertThrows(JsonbException.class, () -> bridge.fromJsonValue(Json.createValue("x"), LocalDate.class));
    }

    abstract static class TypeLiteral<T> {
        Type type() {
            return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }
}
