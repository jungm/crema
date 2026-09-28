package io.github.jungm.crema.internal.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * An application method that implements a Feature or a completion, with its parameters and the MCP Servers it
 * is bound to.
 */
public final class FeatureMethod {

    private final Class<?> beanClass;
    private final Method method;
    private final List<Param> params;
    private final Set<String> servers;
    private final InstanceSource instances;

    public FeatureMethod(Class<?> beanClass, Method method, List<Param> params, Set<String> servers,
            InstanceSource instances) {
        this.beanClass = beanClass;
        this.method = method;
        method.trySetAccessible();
        this.params = List.copyOf(params);
        this.servers = Set.copyOf(servers);
        this.instances = instances;
    }

    public Method method() {
        return method;
    }

    public List<Param> params() {
        return params;
    }

    public List<Param.Argument> arguments() {
        return params.stream().filter(Param.Argument.class::isInstance).map(Param.Argument.class::cast).toList();
    }

    /**
     * The names of the MCP Servers this method is bound to.
     */
    public Set<String> servers() {
        return servers;
    }

    /**
     * The generic type of the values the method returns, with a {@code CompletionStage<T>} unwrapped to {@code T}.
     */
    public Type valueType() {
        return FeatureScanner.valueType(method);
    }

    public boolean acceptsProgress() {
        return params.contains(Param.Injected.PROGRESS);
    }

    /**
     * Invokes the method on a fresh instance and, if it returns a {@link CompletionStage}, awaits its result.
     *
     * @throws Exception whatever the method or the awaited stage throws; an {@link Error} propagates as is, and any
     *         other {@code Throwable} is wrapped in an {@link UndeclaredThrowableException}
     */
    public Object invoke(Object[] args) throws Exception {
        try (InstanceSource.Handle handle = instances.acquire()) {
            Object result;
            try {
                result = method.invoke(handle.get(), args);
            } catch (InvocationTargetException e) {
                throw unwrap(e.getCause());
            }
            if (result instanceof CompletionStage<?> stage) {
                try {
                    return stage.toCompletableFuture().get();
                } catch (ExecutionException e) {
                    throw unwrap(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
            return result;
        }
    }

    /**
     * Names the method for messages, e.g. {@code com.example.Orders#find(String, int)}.
     */
    public String describe() {
        return describe(beanClass, method);
    }

    static String describe(Class<?> beanClass, Method method) {
        return beanClass.getName() + "#" + method.getName() + Arrays.stream(method.getParameters())
                .map(Parameter::getType).map(Class::getSimpleName).collect(Collectors.joining(", ", "(", ")"));
    }

    private static Exception unwrap(Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new UndeclaredThrowableException(cause);
    }

    @Override
    public String toString() {
        return describe();
    }
}
