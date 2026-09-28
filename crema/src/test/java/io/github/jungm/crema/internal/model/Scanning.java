package io.github.jungm.crema.internal.model;

import java.lang.reflect.Method;

/**
 * Scans test fixtures the way the CDI extension scans beans: method by method, each invoked on a fixed instance.
 */
public final class Scanning {

    private Scanning() {
    }

    /**
     * Scans the methods {@code type} declares, invoking Feature Methods on {@code instance}.
     */
    public static FeatureScanner scan(FeatureScanner scanner, Class<?> type, Object instance) {
        InstanceSource instances = instance(instance);
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                scanner.scan(type, method, instances);
            }
        }
        return scanner;
    }

    /**
     * A source that always yields {@code instance}.
     */
    public static InstanceSource instance(Object instance) {
        return () -> new InstanceSource.Handle() {
            @Override
            public Object get() {
                return instance;
            }

            @Override
            public void close() {
            }
        };
    }
}
