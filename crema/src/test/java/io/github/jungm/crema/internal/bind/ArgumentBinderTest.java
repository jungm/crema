package io.github.jungm.crema.internal.bind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ArgumentBinderTest {

    private final JsonbBridge bridge = new JsonbBridge();
    private final ArgumentBinder binder = new ArgumentBinder(bridge);

    public enum Color { RED, GREEN }

    public record Point(int x, int y) {
    }

    public static class Pojo {
        public String name;
        public int count;
        public List<Point> points;
    }

    @AfterEach
    void close() throws Exception {
        bridge.close();
    }

    @Test
    void strings() {
        assertEquals("abc", bind("\"abc\"", String.class));
        assertEquals("5", bind("5", String.class));
        assertEquals("true", bind("true", String.class));
        assertNull(bind("null", String.class));
        assertError("expected a string but got an object", "{}", String.class);
        assertError("expected a string but got an array", "[]", String.class);
    }

    @Test
    void integers() {
        assertEquals(5, bind("5", int.class));
        assertEquals(5, bind("5.0", Integer.class));
        assertEquals(5, bind("\"5\"", int.class));
        assertEquals(-3L, bind("-3", long.class));
        assertEquals((short) 7, bind("7", short.class));
        assertEquals((byte) -128, bind("-128", byte.class));
        assertEquals(new BigInteger("123456789012345678901234567890"),
                bind("123456789012345678901234567890", BigInteger.class));
        assertEquals(100, bind("1e2", int.class));
        assertError("expected an integer but got \"abc\"", "\"abc\"", int.class);
        assertError("expected an integer but got 5.5", "5.5", int.class);
        assertError("expected an integer but got true", "true", Integer.class);
        assertError("expected an integer between -2147483648 and 2147483647 but got 3000000000", "3000000000",
                int.class);
        assertError("expected an integer between -128 and 127 but got 128", "128", byte.class);
        assertError("expected an integer between -9223372036854775808 and 9223372036854775807 but got 1E+400",
                "1e400", long.class);
        assertError("expected an integer with at most 10000 digits but got 1E+100000", "1e100000", BigInteger.class);
    }

    @Test
    void decimals() {
        assertEquals(2.5, bind("2.5", double.class));
        assertEquals(2.5f, bind("2.5", Float.class));
        assertEquals(3.0, bind("\"3\"", double.class));
        assertEquals(new BigDecimal("1.50"), bind("1.50", BigDecimal.class));
        assertEquals(new BigDecimal("7"), bind("7", Number.class));
        assertError("expected a number but got \"x\"", "\"x\"", double.class);
        assertError("expected a number within the range of a double but got 1E+400", "1e400", double.class);
        assertError("expected a number within the range of a float but got 1E+50", "1e50", float.class);
    }

    @Test
    void booleans() {
        assertEquals(true, bind("true", boolean.class));
        assertEquals(false, bind("\"false\"", Boolean.class));
        assertEquals(true, bind("\"TRUE\"", boolean.class));
        assertError("expected a boolean but got 1", "1", boolean.class);
        assertError("expected a boolean but got \"yes\"", "\"yes\"", boolean.class);
    }

    @Test
    void characters() {
        assertEquals('x', bind("\"x\"", char.class));
        assertEquals('y', bind("\"y\"", Character.class));
        assertError("expected a single character but got \"xy\"", "\"xy\"", char.class);
        assertError("expected a single character but got 1", "1", char.class);
    }

    @Test
    void enums() {
        assertEquals(Color.GREEN, bind("\"GREEN\"", Color.class));
        assertError("expected one of \"RED\", \"GREEN\" but got \"BLUE\"", "\"BLUE\"", Color.class);
        assertError("expected one of \"RED\", \"GREEN\" but got \"red\"", "\"red\"", Color.class);
    }

    @Test
    void primitivesRejectNull() {
        assertError("expected an integer but got null", "null", int.class);
        assertError("expected a boolean but got null", "null", boolean.class);
        assertError("expected a number but got null", "null", double.class);
        assertError("expected a single character but got null", "null", char.class);
        assertError("expected an integer but got null", null, long.class);
    }

    @Test
    void referenceTypesAcceptNull() {
        assertNull(bind("null", Integer.class));
        assertNull(bind("null", Point.class));
        assertNull(bind("null", List.class));
        assertNull(binder.bind(null, String.class));
    }

    @Test
    void optionals() {
        assertEquals(Optional.of("a"), bind("\"a\"", new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals(Optional.empty(), bind("null", new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals(Optional.of(new Point(1, 2)),
                bind("{\"x\":1,\"y\":2}", new TypeLiteral<Optional<Point>>() { }.type()));
        assertEquals(Optional.of(new BigDecimal("1")), bind("1", Optional.class));
        assertEquals(OptionalInt.of(3), bind("3", OptionalInt.class));
        assertEquals(OptionalInt.empty(), bind("null", OptionalInt.class));
        assertEquals(OptionalLong.of(3), bind("3", OptionalLong.class));
        assertEquals(OptionalDouble.of(1.5), bind("1.5", OptionalDouble.class));
        assertError("expected an integer but got \"x\"", "\"x\"", OptionalInt.class);
    }

    @Test
    void jsonValues() {
        assertSame(JsonValue.NULL, bind("null", JsonValue.class));
        assertEquals(Json.createObjectBuilder().add("a", 1).build(), bind("{\"a\":1}", JsonObject.class));
        assertEquals(Json.createValue(1), bind("1", JsonValue.class));
        assertNull(bind("null", JsonObject.class));
        assertError("expected an object but got an array", "[]", JsonObject.class);
        assertError("expected an array but got 1", "1", JsonArray.class);
    }

    @Test
    void untyped() {
        assertEquals(Map.of("a", List.of(new BigDecimal("1"), "b")), bind("{\"a\":[1,\"b\"]}", Object.class));
        assertEquals(new BigDecimal("1.5"), bind("1.5", Object.class));
        assertEquals(true, bind("true", Object.class));
    }

    @Test
    void arrays() {
        assertArrayEquals(new int[] {1, 2}, (int[]) bind("[1,2]", int[].class));
        assertArrayEquals(new String[] {"a", null}, (String[]) bind("[\"a\",null]", String[].class));
        assertArrayEquals(new byte[] {1, -1}, (byte[]) bind("[1,-1]", byte[].class));
        Object nested = bind("[[1],[2,3]]", new TypeLiteral<List<Integer>[]>() { }.type());
        assertEquals(List.of(List.of(1), List.of(2, 3)), List.of((Object[]) nested));
        assertError("[1]: expected an integer but got null", "[1,null]", int[].class);
        assertError("expected an array but got \"a\"", "\"a\"", int[].class);
    }

    @Test
    void collections() {
        Object list = bind("[1,2]", new TypeLiteral<List<Integer>>() { }.type());
        assertEquals(List.of(1, 2), list);
        assertInstanceOf(ArrayList.class, list);
        Object set = bind("[\"b\",\"a\",\"b\"]", new TypeLiteral<Set<String>>() { }.type());
        assertEquals(new ArrayList<>(List.of("b", "a")), new ArrayList<>((Set<?>) set));
        assertInstanceOf(LinkedHashSet.class, set);
        assertInstanceOf(TreeSet.class, bind("[\"b\"]", new TypeLiteral<SortedSet<String>>() { }.type()));
        assertEquals(List.of(Color.RED), bind("[\"RED\"]", new TypeLiteral<Collection<Color>>() { }.type()));
        assertEquals(List.of(new Point(1, 2)), bind("[{\"x\":1,\"y\":2}]", new TypeLiteral<List<Point>>() { }.type()));
        assertEquals(List.of(new BigDecimal("1")), bind("[1]", List.class));
        assertError("[1]: expected an integer but got \"x\"", "[1,\"x\"]", new TypeLiteral<List<Integer>>() { }.type());
        assertError("[0]: element can't be added: null", "[null]", new TypeLiteral<SortedSet<String>>() { }.type());
        assertError("expected an array but got an object", "{}", new TypeLiteral<List<Integer>>() { }.type());
    }

    @Test
    void maps() {
        assertEquals(Map.of("a", 1), bind("{\"a\":1}", new TypeLiteral<Map<String, Integer>>() { }.type()));
        assertEquals(Map.of("a", List.of(1)),
                bind("{\"a\":[1]}", new TypeLiteral<Map<String, List<Integer>>>() { }.type()));
        assertEquals(Map.of(Color.RED, 1), bind("{\"RED\":1}", new TypeLiteral<Map<Color, Integer>>() { }.type()));
        assertError("a.b[0]: expected an integer but got \"x\"", "{\"a\":{\"b\":[\"x\"]}}",
                new TypeLiteral<Map<String, Map<String, List<Integer>>>>() { }.type());
        assertError("expected an object but got 1", "1", new TypeLiteral<Map<String, Integer>>() { }.type());
    }

    @Test
    void applicationClassesUseJsonb() {
        Pojo pojo = (Pojo) bind("{\"name\":\"n\",\"count\":2,\"points\":[{\"x\":1,\"y\":2}],\"unknown\":1}",
                Pojo.class);
        assertEquals("n", pojo.name);
        assertEquals(2, pojo.count);
        assertEquals(List.of(new Point(1, 2)), pojo.points);
        assertEquals(LocalDate.of(2024, 2, 29), bind("\"2024-02-29\"", LocalDate.class));
        assertError("expected an object but got \"x\"", "\"x\"", Pojo.class);
        assertError("expected an object but got an array", "[]", Point.class);
        BindingException e = assertThrows(BindingException.class,
                () -> bind("{\"count\":\"abc\"}", Pojo.class));
        assertTrue(e.getMessage().startsWith("invalid value: "), e.getMessage());
        assertThrows(BindingException.class, () -> bind("\"not a date\"", LocalDate.class));
    }

    @Test
    void defaultValues() {
        assertEquals("hello", binder.bindDefault("hello", String.class));
        assertEquals("hello", binder.bindDefault("\"hello\"", String.class));
        assertEquals("123", binder.bindDefault("123", String.class));
        assertEquals("null", binder.bindDefault("null", String.class));
        assertEquals("", binder.bindDefault("", String.class));
        assertEquals(Optional.of("a"), binder.bindDefault("a", new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals('x', binder.bindDefault("x", char.class));
        assertEquals(Color.RED, binder.bindDefault("RED", Color.class));
        assertEquals(Color.GREEN, binder.bindDefault("\"GREEN\"", Color.class));
        assertEquals(10, binder.bindDefault("10", int.class));
        assertEquals(OptionalInt.of(10), binder.bindDefault("10", OptionalInt.class));
        assertEquals(true, binder.bindDefault("true", boolean.class));
        assertNull(binder.bindDefault("null", Integer.class));
        assertEquals(List.of(1, 2), binder.bindDefault("[1, 2]", new TypeLiteral<List<Integer>>() { }.type()));
        assertEquals(new Point(1, 2), binder.bindDefault("{\"x\":1,\"y\":2}", Point.class));
    }

    @Test
    void stringValues() {
        Type optional = new TypeLiteral<Optional<String>>() { }.type();
        for (String raw : List.of("hello", "\"hello\"", "123", "null", "", "[1]")) {
            assertEquals(raw, binder.bindString(raw, String.class), raw);
            assertEquals(raw, binder.bindString(raw, CharSequence.class), raw);
            assertEquals(Optional.of(raw), binder.bindString(raw, optional), raw);
        }
        assertEquals(10, binder.bindString("10", int.class));
        assertEquals(Color.GREEN, binder.bindString("GREEN", Color.class));
        assertEquals(Optional.of(Color.RED), binder.bindString("\"RED\"",
                new TypeLiteral<Optional<Color>>() { }.type()));
    }

    @Test
    void invalidDefaultValues() {
        assertEquals("invalid default value \"abc\": not a JSON value",
                assertThrows(BindingException.class, () -> binder.bindDefault("abc", int.class)).getMessage());
        assertEquals("invalid default value \"1 2\": not a JSON value",
                assertThrows(BindingException.class, () -> binder.bindDefault("1 2", int.class)).getMessage());
        assertEquals("invalid default value \"\": not a JSON value",
                assertThrows(BindingException.class, () -> binder.bindDefault("", int.class)).getMessage());
        assertEquals("invalid default value \"BLUE\": expected one of \"RED\", \"GREEN\" but got \"BLUE\"",
                assertThrows(BindingException.class, () -> binder.bindDefault("BLUE", Color.class)).getMessage());
        assertEquals("invalid default value \"1.5\": expected an integer but got 1.5",
                assertThrows(BindingException.class, () -> binder.bindDefault("1.5", int.class)).getMessage());
        assertThrows(BindingException.class, () -> binder.bindDefault("null", int.class));
    }

    @Test
    void absentValues() {
        assertNull(binder.absent(String.class));
        assertNull(binder.absent(Point.class));
        assertEquals(Optional.empty(), binder.absent(new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals(OptionalInt.empty(), binder.absent(OptionalInt.class));
        assertEquals(OptionalLong.empty(), binder.absent(OptionalLong.class));
        assertEquals(OptionalDouble.empty(), binder.absent(OptionalDouble.class));
        assertEquals("a value is required",
                assertThrows(BindingException.class, () -> binder.absent(int.class)).getMessage());
    }

    @Test
    void optionalTypes() {
        assertTrue(binder.isOptionalType(new TypeLiteral<Optional<String>>() { }.type()));
        assertTrue(binder.isOptionalType(Optional.class));
        assertTrue(binder.isOptionalType(OptionalInt.class));
        assertTrue(binder.isOptionalType(OptionalLong.class));
        assertTrue(binder.isOptionalType(OptionalDouble.class));
        assertFalse(binder.isOptionalType(String.class));
        assertFalse(binder.isOptionalType(int.class));
    }

    @Test
    void longStringsAreTruncatedInMessages() {
        String longString = "x".repeat(100);
        BindingException e = assertThrows(BindingException.class, () -> bind("\"" + longString + "\"", int.class));
        assertEquals("expected an integer but got \"" + "x".repeat(60) + "…\"", e.getMessage());
    }

    @Test
    void pathsAreExposed() {
        BindingException e = assertThrows(BindingException.class,
                () -> bind("[{\"a\":[1,\"x\"]}]", new TypeLiteral<List<Map<String, List<Integer>>>>() { }.type()));
        assertEquals("[0].a[1]", e.path());
        assertEquals("expected an integer but got \"x\"", e.detail());
    }

    private Object bind(String json, Type type) {
        JsonValue value = json == null ? null : Json.createReader(new StringReader(json)).readValue();
        return binder.bind(value, type);
    }

    private void assertError(String message, String json, Type type) {
        BindingException e = assertThrows(BindingException.class, () -> bind(json, type));
        assertEquals(message, e.getMessage());
    }

    abstract static class TypeLiteral<T> {
        Type type() {
            return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }
}
