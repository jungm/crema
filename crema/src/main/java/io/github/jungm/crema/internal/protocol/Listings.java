package io.github.jungm.crema.internal.protocol;

import java.util.Collection;
import java.util.function.Function;

import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.model.Feature;
import io.github.jungm.crema.internal.model.McpServerModel;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * {@code server/discover} and the list methods. Their results are cacheable and carry {@code ttlMs} and
 * {@code cacheScope}; lists hold only the Features the caller may use, sorted by name, without pagination.
 */
final class Listings {

    private Listings() {
    }

    static JsonObject discover(Call call) {
        McpServerModel server = call.server();
        JsonObject empty = Json.object().build();
        JsonObjectBuilder result = Json.object()
                .add("supportedVersions", Json.FACTORY.createArrayBuilder().add(Dispatcher.PROTOCOL_VERSION))
                .add("capabilities", Json.object().add("tools", empty).add("resources", empty).add("prompts", empty)
                        .add("completions", empty));
        String instructions = server.settings().instructions();
        if (instructions != null) {
            result.add("instructions", instructions);
        }
        return cacheable(result, call, server.listTtlMs()).build();
    }

    static JsonObject tools(Call call) {
        return list(call, "tools", call.server().tools(), Feature.Tool::definition);
    }

    static JsonObject resources(Call call) {
        return list(call, "resources", call.server().resources(), Feature.Resource::definition);
    }

    static JsonObject resourceTemplates(Call call) {
        return list(call, "resourceTemplates", call.server().resourceTemplates(),
                Feature.ResourceTemplate::definition);
    }

    static JsonObject prompts(Call call) {
        return list(call, "prompts", call.server().prompts(), Feature.Prompt::definition);
    }

    /**
     * Adds {@code ttlMs} and {@code cacheScope}: {@code private} if the result may depend on the caller, else
     * {@code public}.
     */
    static JsonObjectBuilder cacheable(JsonObjectBuilder result, Call call, long ttlMs) {
        return result.add("ttlMs", ttlMs).add("cacheScope",
                call.services().access().isPrivate(call.server(), call.caller()) ? "private" : "public");
    }

    private static <F extends Feature> JsonObject list(Call call, String key, Collection<F> features,
            Function<F, JsonObject> definition) {
        call.rejectCursor();
        JsonArrayBuilder array = Json.FACTORY.createArrayBuilder();
        for (F feature : features) {
            if (call.services().access().permits(call.server(), feature, call.caller())) {
                array.add(definition.apply(feature));
            }
        }
        return cacheable(Json.object().add(key, array), call, call.server().listTtlMs()).build();
    }
}
