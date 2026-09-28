package io.github.jungm.crema.it.security.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.security.enterprise.authentication.mechanism.http.OpenIdAuthenticationMechanismDefinition;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Defines the WAR's Jakarta Security OpenID Connect mechanism. The provider URI is only known at test time,
 * so it comes from MicroProfile Config through an EL expression.
 */
@ApplicationScoped
@Named("oidcConfig")
@OpenIdAuthenticationMechanismDefinition(
        providerURI = "${oidcConfig.providerUri}",
        clientId = "crema-it",
        clientSecret = "crema-it-secret",
        redirectURI = "${baseURL}/callback")
public class OidcConfig {

    public String getProviderUri() {
        return ConfigProvider.getConfig().getValue("it.oidc.provider-uri", String.class);
    }
}
