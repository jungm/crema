package io.github.jungm.crema.internal.invoke;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.mcpjava.server.McpRequest;
import org.mcpjava.server.completion.CompletionContext;
import org.mcpjava.server.progress.Progress;

import io.github.jungm.crema.internal.bind.ArgumentBinder;
import io.github.jungm.crema.internal.bind.BindingException;
import io.github.jungm.crema.internal.model.FeatureMethod;
import io.github.jungm.crema.internal.model.Param;
import io.github.jungm.crema.internal.security.Caller;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * Supplies the parameters of Feature Methods and Completion Methods for one request.
 */
public final class Invocation {

    private final McpRequest request;
    private final Progress progress;
    private final CompletionContext completionContext;
    private final Caller caller;

    /**
     * An invocation for an anonymous caller.
     *
     * @param completionContext the context of a {@code completion/complete} request, or {@code null}
     */
    public Invocation(McpRequest request, Progress progress, CompletionContext completionContext) {
        this(request, progress, completionContext, Caller.ANONYMOUS);
    }

    /**
     * @param completionContext the context of a {@code completion/complete} request, or {@code null}
     * @param caller the caller the request was admitted for
     */
    public Invocation(McpRequest request, Progress progress, CompletionContext completionContext, Caller caller) {
        this.request = request;
        this.progress = progress;
        this.completionContext = completionContext;
        this.caller = caller;
    }

    /**
     * Invokes a method, obtaining each Argument from {@code arguments}.
     *
     * @throws BindingException if an Argument is missing or can't be bound, before the method is invoked
     * @throws Exception whatever the method throws
     */
    public Object invoke(FeatureMethod method, Function<Param.Argument, Object> arguments) throws Exception {
        List<Param> params = method.params();
        Object[] values = new Object[params.size()];
        for (int i = 0; i < values.length; i++) {
            Param param = params.get(i);
            if (param instanceof Param.Argument argument) {
                values[i] = arguments.apply(argument);
            } else {
                values[i] = injected((Param.Injected) param);
            }
        }
        return method.invoke(values);
    }

    private Object injected(Param.Injected param) {
        switch (param) {
            case MCP_REQUEST:
                return request;
            case PROGRESS:
                return progress;
            case CANCELLATION:
                return NeverCancelled.INSTANCE;
            case CALLER:
                return caller.mcpCaller();
            case PRINCIPAL:
                return principal();
            default:
                return completionContext;
        }
    }

    /**
     * The caller's principal, or {@code null} if the caller is anonymous. Some Runtimes throw instead of
     * answering {@code null}.
     */
    private Principal principal() {
        try {
            return caller.principal();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Arguments from a JSON object, bound to the parameter types. A JSON {@code null} for a required Argument
     * counts as missing, unless {@code null} is a value of its type.
     *
     * @param json the {@code arguments} object, or {@code null} when the request has none
     */
    public static Function<Param.Argument, Object> fromJson(JsonObject json, ArgumentBinder binder) {
        return argument -> {
            JsonValue value = json == null ? null : json.get(argument.name());
            if (value == null) {
                return absent(argument, binder);
            }
            if (value.getValueType() == JsonValue.ValueType.NULL && argument.required()
                    && !binder.acceptsNull(argument.type())) {
                throw missing(argument);
            }
            try {
                return binder.bind(value, argument.type());
            } catch (BindingException e) {
                throw e.atMember(argument.name());
            }
        };
    }

    /**
     * Arguments from string values, such as Prompt arguments and URI template variables, bound with
     * {@link ArgumentBinder#bindString(String, java.lang.reflect.Type)}.
     */
    public static Function<Param.Argument, Object> fromStrings(Map<String, String> strings, ArgumentBinder binder) {
        return argument -> {
            String value = strings.get(argument.name());
            if (value == null) {
                return absent(argument, binder);
            }
            try {
                return binder.bindString(value, argument.type());
            } catch (BindingException e) {
                throw e.atMember(argument.name());
            }
        };
    }

    private static Object absent(Param.Argument argument, ArgumentBinder binder) {
        if (argument.defaultValue() != null) {
            return binder.bindDefault(argument.defaultValue(), argument.type());
        }
        if (argument.required()) {
            throw missing(argument);
        }
        return binder.absent(argument.type());
    }

    private static BindingException missing(Param.Argument argument) {
        return new BindingException("Missing required argument '" + argument.name() + "'");
    }
}
