package io.github.jungm.crema.internal.bind;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Generic type resolution following the JSON-B type resolution algorithm (JSON-B 3.0, section 3.17.1).
 * <p>
 * A <em>resolved</em> type contains only {@link Class}, {@link ParameterizedType} and {@link GenericArrayType}
 * nodes: type variables are replaced by their binding, or by their first bound when unbound, and wildcards by their
 * upper bound. Resolved types have value semantics ({@code equals}/{@code hashCode} compatible with the JDK's own
 * implementations), so they can serve as map keys.
 */
public final class Types {

    private Types() {
    }

    /**
     * Resolves a type without any known type variable bindings.
     */
    public static Type resolve(Type type) {
        return resolve(type, Map.of());
    }

    /**
     * Resolves a type, substituting type variables from {@code bindings}.
     */
    public static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof Class<?>) {
            return type;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = resolve(arguments[i], bindings);
            }
            Type owner = parameterized.getOwnerType() == null ? null : resolve(parameterized.getOwnerType(), bindings);
            return new ResolvedParameterizedType((Class<?>) parameterized.getRawType(), resolved, owner);
        }
        if (type instanceof GenericArrayType) {
            Type component = resolve(((GenericArrayType) type).getGenericComponentType(), bindings);
            if (component instanceof Class<?>) {
                return Array.newInstance((Class<?>) component, 0).getClass();
            }
            return new ResolvedGenericArrayType(component);
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length == 0 ? Object.class : resolve(upper[0], bindings);
        }
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) type;
            Type bound = bindings.get(variable);
            if (bound != null) {
                return bound;
            }
            Type[] bounds = variable.getBounds();
            if (bounds.length == 0 || bounds[0] == Object.class) {
                return Object.class;
            }
            // Guard against self-referencing bounds such as T extends Comparable<T>.
            Map<TypeVariable<?>, Type> guarded = new HashMap<>(bindings);
            guarded.put(variable, Object.class);
            return resolve(bounds[0], guarded);
        }
        return Object.class;
    }

    /**
     * Returns the type variable bindings a resolved type establishes for its raw class (and enclosing classes).
     * Type parameters of a raw class are bound to their resolved bounds.
     */
    public static Map<TypeVariable<?>, Type> bindings(Type type) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        addBindings(type, bindings);
        return bindings;
    }

    private static void addBindings(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (parameterized.getOwnerType() != null) {
                addBindings(parameterized.getOwnerType(), bindings);
            }
            TypeVariable<?>[] parameters = ((Class<?>) parameterized.getRawType()).getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < parameters.length && i < arguments.length; i++) {
                bindings.put(parameters[i], resolve(arguments[i], bindings));
            }
        } else if (type instanceof Class<?>) {
            for (TypeVariable<?> parameter : ((Class<?>) type).getTypeParameters()) {
                bindings.put(parameter, resolve(parameter, bindings));
            }
        }
    }

    /**
     * Returns the erasure of a type.
     */
    public static Class<?> rawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Array.newInstance(rawType(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length == 0 ? Object.class : rawType(upper[0]);
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : rawType(bounds[0]);
        }
        return Object.class;
    }

    /**
     * Returns the resolved type arguments with which {@code type} implements or extends {@code target}, or
     * {@code null} if it doesn't. For example, {@code typeArguments(ArrayList<String>, Collection.class)} is
     * {@code [String]}.
     */
    public static Type[] typeArguments(Type type, Class<?> target) {
        Type resolved = resolve(type);
        Class<?> raw = rawType(resolved);
        if (!target.isAssignableFrom(raw)) {
            return null;
        }
        return findTypeArguments(resolved, target);
    }

    private static Type[] findTypeArguments(Type resolved, Class<?> target) {
        Class<?> raw = rawType(resolved);
        if (raw == target) {
            if (resolved instanceof ParameterizedType) {
                return ((ParameterizedType) resolved).getActualTypeArguments();
            }
            TypeVariable<?>[] parameters = raw.getTypeParameters();
            Type[] arguments = new Type[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = resolve(parameters[i]);
            }
            return arguments;
        }
        Map<TypeVariable<?>, Type> bindings = bindings(resolved);
        if (raw.getGenericSuperclass() != null && target.isAssignableFrom(raw.getSuperclass())) {
            return findTypeArguments(resolve(raw.getGenericSuperclass(), bindings), target);
        }
        for (Type supertype : raw.getGenericInterfaces()) {
            if (target.isAssignableFrom(rawType(supertype))) {
                return findTypeArguments(resolve(supertype, bindings), target);
            }
        }
        return null;
    }

    /**
     * Returns the resolved component type of an array type, or {@code null} if the type is not an array.
     */
    public static Type arrayComponentType(Type type) {
        if (type instanceof Class<?>) {
            return ((Class<?>) type).getComponentType();
        }
        if (type instanceof GenericArrayType) {
            return resolve(((GenericArrayType) type).getGenericComponentType());
        }
        return null;
    }

    /**
     * Returns whether the type is {@code Optional}, {@code OptionalInt}, {@code OptionalLong} or
     * {@code OptionalDouble}.
     */
    public static boolean isOptional(Type type) {
        Class<?> raw = rawType(type);
        return raw == Optional.class || raw == OptionalInt.class || raw == OptionalLong.class
                || raw == OptionalDouble.class;
    }

    /**
     * Returns the empty value of an {@code Optional*} type ({@code Optional.empty()}, {@code OptionalInt.empty()},
     * ...), or {@code null} for any other type.
     */
    public static Object emptyOptional(Type type) {
        Class<?> raw = rawType(type);
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
     * Returns whether a class belongs to the Java platform or Jakarta EE ({@code java.*}, {@code javax.*},
     * {@code jakarta.*}, {@code jdk.*}, {@code sun.*}, {@code com.sun.*}). JSON-B maps only some of these, all of
     * them explicitly; the rest are never application classes that JSON-B maps as JSON objects.
     */
    public static boolean isPlatformClass(Class<?> raw) {
        String name = raw.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    /**
     * Returns the type an {@code Optional*} type wraps ({@code int} for {@code OptionalInt}, {@code T} for
     * {@code Optional<T>}), or {@code null} if the type is not an {@code Optional*} type.
     */
    public static Type optionalValueType(Type type) {
        Class<?> raw = rawType(type);
        if (raw == Optional.class) {
            Type[] arguments = typeArguments(type, Optional.class);
            return arguments == null || arguments.length == 0 ? Object.class : arguments[0];
        }
        if (raw == OptionalInt.class) {
            return int.class;
        }
        if (raw == OptionalLong.class) {
            return long.class;
        }
        if (raw == OptionalDouble.class) {
            return double.class;
        }
        return null;
    }

    private static final class ResolvedParameterizedType implements ParameterizedType {

        private final Class<?> rawType;
        private final Type[] arguments;
        private final Type ownerType;

        ResolvedParameterizedType(Class<?> rawType, Type[] arguments, Type ownerType) {
            this.rawType = rawType;
            this.arguments = arguments;
            this.ownerType = ownerType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType that = (ParameterizedType) other;
            return rawType.equals(that.getRawType())
                    && Objects.equals(ownerType, that.getOwnerType())
                    && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(ownerType) ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder(rawType.getTypeName()).append('<');
            for (int i = 0; i < arguments.length; i++) {
                builder.append(i == 0 ? "" : ", ").append(arguments[i].getTypeName());
            }
            return builder.append('>').toString();
        }
    }

    private static final class ResolvedGenericArrayType implements GenericArrayType {

        private final Type componentType;

        ResolvedGenericArrayType(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType
                    && componentType.equals(((GenericArrayType) other).getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return componentType.hashCode();
        }

        @Override
        public String toString() {
            return componentType.getTypeName() + "[]";
        }
    }
}
