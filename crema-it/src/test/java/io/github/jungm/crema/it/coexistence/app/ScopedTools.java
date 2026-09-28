package io.github.jungm.crema.it.coexistence.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.mcpjava.server.tools.Tool;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;

/**
 * A {@code @RequestScoped} Feature bean with an interceptor-bound Tool: the Tool counts its calls in a request-scoped
 * counter that the interceptor counts in as well, so each request reports {@code 2}.
 */
@RequestScoped
public class ScopedTools {

    @Inject
    Counter counter;

    @Tool(description = "Counts calls in the request, including the interceptor's")
    @Counted
    public String counted() {
        return "count " + counter.increment();
    }

    /** Counts within one request. */
    @RequestScoped
    public static class Counter {

        private int count;

        public int increment() {
            return ++count;
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {
    }

    /** Counts once before the intercepted method. */
    @Interceptor
    @Counted
    @Priority(Interceptor.Priority.APPLICATION)
    public static class CountingInterceptor {

        @Inject
        Counter counter;

        @AroundInvoke
        public Object count(InvocationContext context) throws Exception {
            counter.increment();
            return context.proceed();
        }
    }
}
