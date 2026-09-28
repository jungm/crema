package io.github.jungm.crema.internal.bind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import javax.naming.Name;

import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;

class TypesTest {

    abstract static class TypeLiteral<T> {
        Type type() {
            return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }

    static class StringList extends ArrayList<String> {
    }

    static class Swapped<V, K> extends HashMap<K, List<V>> {
    }

    static class Bounded<T extends Number & Comparable<T>> {
    }

    @Test
    void resolvedTypesEqualJdkTypes() {
        Type jdk = new TypeLiteral<Map<String, List<Integer>>>() { }.type();
        Type resolved = Types.resolve(jdk);

        assertEquals(jdk, resolved);
        assertEquals(resolved, jdk);
        assertEquals(jdk.hashCode(), resolved.hashCode());
    }

    @Test
    void wildcardsResolveToUpperBounds() {
        assertEquals(new TypeLiteral<List<Number>>() { }.type(),
                Types.resolve(new TypeLiteral<List<? extends Number>>() { }.type()));
        assertEquals(new TypeLiteral<List<Object>>() { }.type(),
                Types.resolve(new TypeLiteral<List<? super Number>>() { }.type()));
        assertEquals(new TypeLiteral<List<Object>>() { }.type(),
                Types.resolve(new TypeLiteral<List<?>>() { }.type()));
    }

    @Test
    void unboundTypeVariablesResolveToTheirFirstBound() {
        Type t = Bounded.class.getTypeParameters()[0];
        assertEquals(Number.class, Types.resolve(t));
        assertEquals(Object.class, Types.resolve(List.class.getTypeParameters()[0]));
    }

    @Test
    void genericArraysOfClassesBecomeArrayClasses() {
        assertEquals(String[].class, Types.resolve(new TypeLiteral<String[]>() { }.type()));
        Type listArray = Types.resolve(new TypeLiteral<List<String>[]>() { }.type());
        assertEquals(new TypeLiteral<List<String>>() { }.type(), Types.arrayComponentType(listArray));
        assertEquals(List[].class, Types.rawType(listArray));
    }

    @Test
    void typeArgumentsOfSupertypes() {
        assertArrayEquals(new Type[] {String.class}, Types.typeArguments(StringList.class, Collection.class));
        assertArrayEquals(new Type[] {Integer.class, new TypeLiteral<List<String>>() { }.type()},
                Types.typeArguments(new TypeLiteral<Swapped<String, Integer>>() { }.type(), Map.class));
        assertArrayEquals(new Type[] {Object.class}, Types.typeArguments(List.class, Iterable.class));
        assertNull(Types.typeArguments(String.class, Collection.class));
    }

    @Test
    void platformClasses() {
        assertTrue(Types.isPlatformClass(String.class));
        assertTrue(Types.isPlatformClass(JsonObject.class));
        assertTrue(Types.isPlatformClass(Name.class));
        assertFalse(Types.isPlatformClass(TypesTest.class));
    }

    @Test
    void optionals() {
        assertTrue(Types.isOptional(new TypeLiteral<Optional<String>>() { }.type()));
        assertTrue(Types.isOptional(OptionalInt.class));
        assertFalse(Types.isOptional(String.class));
        assertTrue(Types.isOptional(OptionalLong.class));
        assertTrue(Types.isOptional(OptionalDouble.class));
        assertFalse(Types.isOptional(int.class));
        assertEquals(Optional.empty(), Types.emptyOptional(new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals(OptionalInt.empty(), Types.emptyOptional(OptionalInt.class));
        assertEquals(OptionalLong.empty(), Types.emptyOptional(OptionalLong.class));
        assertEquals(OptionalDouble.empty(), Types.emptyOptional(OptionalDouble.class));
        assertNull(Types.emptyOptional(String.class));
        assertEquals(String.class, Types.optionalValueType(new TypeLiteral<Optional<String>>() { }.type()));
        assertEquals(Object.class, Types.optionalValueType(Optional.class));
        assertEquals(int.class, Types.optionalValueType(OptionalInt.class));
        assertNull(Types.optionalValueType(String.class));
    }
}
