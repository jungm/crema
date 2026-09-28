package io.github.jungm.crema.it.coexistence.app;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.config.PropertyNamingStrategy;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** The application's own providers, which must not affect MCP traffic. */
public final class AppProviders {

    public static final String HEADER = "X-App-Filter";

    private AppProviders() {
    }

    /** JSON-B with snake_case property names. */
    @Provider
    public static class SnakeCaseJsonb implements ContextResolver<Jsonb> {
        private final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig()
                .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_CASE_WITH_UNDERSCORES));

        @Override
        public Jsonb getContext(Class<?> type) {
            return jsonb;
        }
    }

    /** Maps every exception to 418. */
    @Provider
    public static class CatchAll implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable exception) {
            return Response.status(418).type(MediaType.TEXT_PLAIN).entity("mapped by the application").build();
        }
    }

    /** Adds a header to every response. */
    @Provider
    public static class HeaderFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle(HEADER, "yes");
        }
    }
}
