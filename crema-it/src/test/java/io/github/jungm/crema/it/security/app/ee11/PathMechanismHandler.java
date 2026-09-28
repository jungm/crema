package io.github.jungm.crema.it.security.app.ee11;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanismHandler;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.authentication.mechanism.http.OpenIdAuthenticationMechanismDefinition.OpenIdAuthenticationMechanism;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Jakarta Security 4.0 (EE 11) handler for Runtimes whose MP-JWT is itself a Jakarta Security
 * {@link HttpAuthenticationMechanism} bean (WildFly/SmallRye JWT). With the application's OpenID Connect mechanism
 * that makes two mechanisms, which the default handler rejects as ambiguous. This handler sends the web UI and the
 * OpenID Connect callback to the OpenID Connect mechanism and everything else to the {@code @Default} mechanism,
 * which is the Runtime's MP-JWT.
 */
@Alternative
@Priority(Interceptor.Priority.APPLICATION)
@ApplicationScoped
public class PathMechanismHandler implements HttpAuthenticationMechanismHandler {

    @Inject
    @OpenIdAuthenticationMechanism
    HttpAuthenticationMechanism openId;

    @Inject
    HttpAuthenticationMechanism mpJwt;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) throws AuthenticationException {
        return select(request).validateRequest(request, response, context);
    }

    @Override
    public AuthenticationStatus secureResponse(HttpServletRequest request, HttpServletResponse response,
                                               HttpMessageContext context) throws AuthenticationException {
        return select(request).secureResponse(request, response, context);
    }

    @Override
    public void cleanSubject(HttpServletRequest request, HttpServletResponse response, HttpMessageContext context) {
        select(request).cleanSubject(request, response, context);
    }

    private HttpAuthenticationMechanism select(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/ui") || path.equals("/callback") ? openId : mpJwt;
    }
}
