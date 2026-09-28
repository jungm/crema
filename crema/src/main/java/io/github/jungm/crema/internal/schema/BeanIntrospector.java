package io.github.jungm.crema.internal.schema;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jungm.crema.internal.bind.Types;
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbNumberFormat;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.annotation.JsonbVisibility;
import jakarta.json.bind.config.PropertyVisibilityStrategy;

/**
 * Finds the JSON properties of a class the way JSON-B's default mapping does (JSON-B 3.0, sections 3.7.1, 3.13 and
 * chapter 4), for one {@link Direction}.
 */
final class BeanIntrospector {

    /**
     * Whether properties are looked at as JSON-B writes them (Tool results) or as it reads them (Arguments).
     */
    enum Direction {
        SERIALIZATION,
        DESERIALIZATION
    }

    /**
     * One JSON property.
     *
     * @param name JSON name
     * @param type resolved Java type of the value
     * @param adaptedType the resolved {@code Adapted} type of a {@code @JsonbTypeAdapter}, else {@code null}
     * @param customSerialization a {@code @JsonbTypeSerializer}/{@code @JsonbTypeDeserializer} applies, so the
     *                            JSON shape is unknown
     * @param nillable a {@code null} value is written as JSON {@code null} rather than omitted
     * @param formats {@code @JsonbDateFormat}/{@code @JsonbNumberFormat} in effect
     */
    record BeanProperty(String name, Type type, Type adaptedType, boolean customSerialization, boolean nillable,
                        Formats formats) {

        /**
         * Returns whether the value can't be {@code null}, so JSON-B always writes this property.
         */
        boolean isPrimitive() {
            return Types.rawType(type).isPrimitive() && adaptedType == null && !customSerialization;
        }
    }

    /**
     * Custom date and number formats applying to a value; {@code null} means the default format.
     */
    record Formats(String dateFormat, String numberFormat) {

        static final Formats DEFAULT = new Formats(null, null);
    }

    private final Direction direction;
    private final Map<Class<?>, PropertyVisibilityStrategy> strategies = new HashMap<>();

    BeanIntrospector(Direction direction) {
        this.direction = direction;
    }

    /**
     * Returns the properties of a resolved class type: superclass properties first, each class's own properties in
     * lexicographical order of their JSON names.
     */
    List<BeanProperty> properties(Type type) {
        List<Level> levels = levels(type);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (int depth = 0; depth < levels.size(); depth++) {
            collect(levels.get(depth), depth, candidates);
        }

        Map<String, Ranked> byName = new LinkedHashMap<>();
        for (Candidate candidate : candidates.values()) {
            Ranked property = direction == Direction.SERIALIZATION ? readable(candidate) : writable(candidate);
            if (property != null) {
                byName.putIfAbsent(property.property.name(), property);
            }
        }
        if (direction == Direction.DESERIALIZATION) {
            addCreatorParameters(type, levels.size(), byName);
        }

        List<Ranked> ranked = new ArrayList<>(byName.values());
        ranked.sort(Comparator.<Ranked>comparingInt(r -> r.depth).thenComparing(r -> r.property.name()));
        List<BeanProperty> properties = new ArrayList<>(ranked.size());
        for (Ranked r : ranked) {
            properties.add(r.property);
        }
        return properties;
    }

    private record Level(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
    }

    private record Ranked(BeanProperty property, int depth) {
    }

    private static final class Candidate {
        final String javaName;
        int depth;
        Class<?> declaringClass;
        Field field;
        Type fieldType;
        Method getter;
        Type getterType;
        Method setter;
        Type setterType;

        Candidate(String javaName) {
            this.javaName = javaName;
        }
    }

    /**
     * Returns the class hierarchy of a type from the topmost superclass (below {@code Object}) down to the type
     * itself; for an interface, the interface and its superinterfaces.
     */
    private static List<Level> levels(Type type) {
        Class<?> raw = Types.rawType(type);
        List<Level> levels = new ArrayList<>();
        if (raw.isInterface()) {
            Deque<Type> pending = new ArrayDeque<>();
            Set<Class<?>> seen = new HashSet<>();
            pending.add(type);
            while (!pending.isEmpty()) {
                Type current = pending.poll();
                Class<?> currentRaw = Types.rawType(current);
                if (seen.add(currentRaw)) {
                    Map<TypeVariable<?>, Type> bindings = Types.bindings(current);
                    levels.add(0, new Level(currentRaw, bindings));
                    for (Type supertype : currentRaw.getGenericInterfaces()) {
                        pending.add(Types.resolve(supertype, bindings));
                    }
                }
            }
            return levels;
        }
        Type current = type;
        while (current != null && Types.rawType(current) != Object.class) {
            Class<?> currentRaw = Types.rawType(current);
            Map<TypeVariable<?>, Type> bindings = Types.bindings(current);
            levels.add(0, new Level(currentRaw, bindings));
            Type supertype = currentRaw.getGenericSuperclass();
            current = supertype == null ? null : Types.resolve(supertype, bindings);
        }
        return levels;
    }

    private static void collect(Level level, int depth, Map<String, Candidate> candidates) {
        Class<?> type = level.type;
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            Candidate candidate = candidate(candidates, field.getName(), depth, type);
            candidate.field = field;
            candidate.fieldType = Types.resolve(field.getGenericType(), level.bindings);
        }
        Set<String> recordComponents = new HashSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                recordComponents.add(component.getName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() == 0 && recordComponents.contains(method.getName())) {
                Candidate candidate = candidate(candidates, method.getName(), depth, type);
                candidate.getter = method;
                candidate.getterType = Types.resolve(method.getGenericReturnType(), level.bindings);
                continue;
            }
            addAccessor(candidates, method, depth, type, level.bindings, false);
        }
        if (!type.isInterface()) {
            for (Level ifc : interfaces(type, level.bindings)) {
                for (Method method : ifc.type.getDeclaredMethods()) {
                    if (method.isDefault()) {
                        addAccessor(candidates, method, depth, type, ifc.bindings, true);
                    }
                }
            }
        }
    }

    private static List<Level> interfaces(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        List<Level> interfaces = new ArrayList<>();
        Deque<Type> pending = new ArrayDeque<>();
        for (Type supertype : type.getGenericInterfaces()) {
            pending.add(Types.resolve(supertype, bindings));
        }
        Set<Class<?>> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Type current = pending.poll();
            Class<?> raw = Types.rawType(current);
            if (seen.add(raw)) {
                Map<TypeVariable<?>, Type> currentBindings = Types.bindings(current);
                interfaces.add(new Level(raw, currentBindings));
                for (Type supertype : raw.getGenericInterfaces()) {
                    pending.add(Types.resolve(supertype, currentBindings));
                }
            }
        }
        return interfaces;
    }

    private static void addAccessor(Map<String, Candidate> candidates, Method method, int depth, Class<?> type,
                                    Map<TypeVariable<?>, Type> bindings, boolean onlyIfAbsent) {
        if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
            return;
        }
        String name = method.getName();
        int parameters = method.getParameterCount();
        Class<?> returnType = method.getReturnType();
        boolean getter = parameters == 0
                && (name.length() > 3 && name.startsWith("get") && returnType != void.class
                    || name.length() > 2 && name.startsWith("is")
                       && (returnType == boolean.class || returnType == Boolean.class));
        boolean setter = parameters == 1 && name.length() > 3 && name.startsWith("set");
        if (!getter && !setter) {
            return;
        }
        String propertyName = decapitalize(name.substring(name.startsWith("is") ? 2 : 3));
        Candidate existing = candidates.get(propertyName);
        if (onlyIfAbsent && existing != null && (getter ? existing.getter : existing.setter) != null) {
            return;
        }
        Candidate candidate = existing != null && onlyIfAbsent
                ? existing : candidate(candidates, propertyName, depth, type);
        if (getter) {
            candidate.getter = method;
            candidate.getterType = Types.resolve(method.getGenericReturnType(), bindings);
        } else {
            candidate.setter = method;
            candidate.setterType = Types.resolve(method.getGenericParameterTypes()[0], bindings);
        }
    }

    private static Candidate candidate(Map<String, Candidate> candidates, String name, int depth, Class<?> type) {
        Candidate candidate = candidates.computeIfAbsent(name, Candidate::new);
        candidate.depth = depth;
        candidate.declaringClass = type;
        return candidate;
    }

    /**
     * Same as {@code java.beans.Introspector.decapitalize}: {@code URL} stays {@code URL}, {@code Name} becomes
     * {@code name}.
     */
    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private Ranked readable(Candidate candidate) {
        Field field = candidate.field;
        if (field != null && (Modifier.isTransient(field.getModifiers()) || Modifier.isStatic(field.getModifiers()))) {
            return null;
        }
        if (isTransient(field) || isTransient(candidate.getter)) {
            return null;
        }
        PropertyVisibilityStrategy strategy = strategy(candidate.declaringClass);
        Type type;
        if (candidate.getter != null && isVisible(candidate.getter, strategy)) {
            type = candidate.getterType;
        } else if (isVisible(field, candidate.getter, strategy)) {
            type = candidate.fieldType;
        } else {
            return null;
        }
        AnnotatedElement accessor = candidate.getter;
        String name = jsonName(candidate.javaName, accessor, field);
        return new Ranked(property(name, type, candidate.declaringClass, accessor, field), candidate.depth);
    }

    private Ranked writable(Candidate candidate) {
        Field field = candidate.field;
        if (field != null && (field.getModifiers() & (Modifier.TRANSIENT | Modifier.STATIC | Modifier.FINAL)) != 0) {
            return null;
        }
        if (isTransient(field) || isTransient(candidate.setter)) {
            return null;
        }
        PropertyVisibilityStrategy strategy = strategy(candidate.declaringClass);
        Type type;
        if (candidate.setter != null && isVisible(candidate.setter, strategy)) {
            type = candidate.setterType;
        } else if (isVisible(field, candidate.setter, strategy)) {
            type = candidate.fieldType;
        } else {
            return null;
        }
        AnnotatedElement accessor = candidate.setter;
        String name = jsonName(candidate.javaName, accessor, field);
        return new Ranked(property(name, type, candidate.declaringClass, accessor, field), candidate.depth);
    }

    private void addCreatorParameters(Type type, int depth, Map<String, Ranked> byName) {
        Class<?> raw = Types.rawType(type);
        Map<TypeVariable<?>, Type> bindings = Types.bindings(type);
        Executable creator = null;
        for (Constructor<?> constructor : raw.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(JsonbCreator.class)) {
                creator = constructor;
            }
        }
        for (Method method : raw.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.isAnnotationPresent(JsonbCreator.class)) {
                creator = method;
            }
        }
        String[] recordNames = null;
        if (creator == null && raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            recordNames = new String[components.length];
            for (int i = 0; i < components.length; i++) {
                parameterTypes[i] = components[i].getType();
                recordNames[i] = components[i].getName();
            }
            try {
                creator = raw.getDeclaredConstructor(parameterTypes);
            } catch (NoSuchMethodException e) {
                return;
            }
        }
        if (creator == null) {
            return;
        }
        Parameter[] parameters = creator.getParameters();
        Type[] parameterTypes = creator.getGenericParameterTypes();
        // Inner-class constructors may report fewer generic parameter types than parameters.
        int offset = parameters.length - parameterTypes.length;
        for (int i = offset; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            JsonbProperty annotation = parameter.getAnnotation(JsonbProperty.class);
            String name;
            if (annotation != null && !annotation.value().isEmpty()) {
                name = annotation.value();
            } else if (recordNames != null) {
                name = recordNames[i];
            } else {
                name = parameter.getName();
            }
            Type parameterType = Types.resolve(parameterTypes[i - offset], bindings);
            byName.remove(name);
            byName.put(name, new Ranked(property(name, parameterType, raw, parameter, null), depth));
        }
    }

    private BeanProperty property(String name, Type type, Class<?> declaringClass, AnnotatedElement accessor,
                                  Field field) {
        Type adaptedType = null;
        JsonbTypeAdapter adapter = annotation(JsonbTypeAdapter.class, accessor, field);
        if (adapter != null) {
            Type[] arguments = Types.typeArguments(adapter.value(), JsonbAdapter.class);
            adaptedType = arguments == null ? Object.class : arguments[1];
        }
        boolean custom = direction == Direction.SERIALIZATION
                ? annotation(JsonbTypeSerializer.class, accessor, field) != null
                : annotation(JsonbTypeDeserializer.class, accessor, field) != null;
        boolean nillable = direction == Direction.SERIALIZATION && isNillable(declaringClass, accessor, field);

        JsonbDateFormat dateFormat = annotation(JsonbDateFormat.class, accessor, field);
        if (dateFormat == null) {
            dateFormat = typeOrPackageAnnotation(JsonbDateFormat.class, declaringClass);
        }
        JsonbNumberFormat numberFormat = annotation(JsonbNumberFormat.class, accessor, field);
        if (numberFormat == null) {
            numberFormat = typeOrPackageAnnotation(JsonbNumberFormat.class, declaringClass);
        }
        String date = dateFormat == null || JsonbDateFormat.DEFAULT_FORMAT.equals(dateFormat.value())
                ? null : dateFormat.value();
        String number = numberFormat == null ? null : numberFormat.value();
        Formats formats = date == null && number == null ? Formats.DEFAULT : new Formats(date, number);
        return new BeanProperty(name, type, adaptedType, custom, nillable, formats);
    }

    /**
     * JSON-B 3.0, section 4.3: the annotation with the smallest scope wins; a property-level {@code @JsonbProperty}
     * counts, with its deprecated {@code nillable} attribute.
     */
    private static boolean isNillable(Class<?> declaringClass, AnnotatedElement accessor, Field field) {
        JsonbNillable nillable = annotation(JsonbNillable.class, accessor, field);
        if (nillable != null) {
            return nillable.value();
        }
        JsonbProperty property = annotation(JsonbProperty.class, accessor, field);
        if (property != null) {
            @SuppressWarnings("deprecation")
            boolean propertyNillable = property.nillable();
            return propertyNillable;
        }
        nillable = typeOrPackageAnnotation(JsonbNillable.class, declaringClass);
        return nillable != null && nillable.value();
    }

    private static String jsonName(String javaName, AnnotatedElement accessor, Field field) {
        JsonbProperty property = annotation(JsonbProperty.class, accessor, field);
        return property == null || property.value().isEmpty() ? javaName : property.value();
    }

    private static boolean isTransient(AnnotatedElement element) {
        return element != null && element.isAnnotationPresent(JsonbTransient.class);
    }

    private static <A extends java.lang.annotation.Annotation> A annotation(Class<A> type, AnnotatedElement accessor,
                                                                            Field field) {
        A annotation = accessor == null ? null : accessor.getAnnotation(type);
        if (annotation == null && field != null) {
            annotation = field.getAnnotation(type);
        }
        return annotation;
    }

    private static <A extends java.lang.annotation.Annotation> A typeOrPackageAnnotation(Class<A> type,
                                                                                         Class<?> declaringClass) {
        A annotation = declaringClass.getAnnotation(type);
        if (annotation == null && declaringClass.getPackage() != null) {
            annotation = declaringClass.getPackage().getAnnotation(type);
        }
        return annotation;
    }

    private PropertyVisibilityStrategy strategy(Class<?> declaringClass) {
        return strategies.computeIfAbsent(declaringClass, type -> {
            JsonbVisibility visibility = typeOrPackageAnnotation(JsonbVisibility.class, type);
            if (visibility == null) {
                return null;
            }
            try {
                Constructor<? extends PropertyVisibilityStrategy> constructor =
                        visibility.value().getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        });
    }

    private static boolean isVisible(Method method, PropertyVisibilityStrategy strategy) {
        return strategy != null ? strategy.isVisible(method) : Modifier.isPublic(method.getModifiers());
    }

    /**
     * JSON-B 3.0, section 3.7.1: with the default strategy a public field is used only if there is no accessor or
     * the accessor is public.
     */
    private static boolean isVisible(Field field, Method accessor, PropertyVisibilityStrategy strategy) {
        if (field == null) {
            return false;
        }
        if (strategy != null) {
            return strategy.isVisible(field);
        }
        return (accessor == null || Modifier.isPublic(accessor.getModifiers()))
                && Modifier.isPublic(field.getModifiers());
    }
}
