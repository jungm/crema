package io.github.jungm.crema.it.security.app;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** An application provider discovered by scanning; marks every response it touches. */
@Provider
public class AppResponseFilter implements ContainerResponseFilter {

    public static final String HEADER = "X-App-Provider";

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle(HEADER, "applied");
    }
}
