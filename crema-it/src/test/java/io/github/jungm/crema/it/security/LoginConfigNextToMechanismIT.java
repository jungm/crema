package io.github.jungm.crema.it.security;

import io.github.jungm.crema.it.support.FakeOidcProvider;
import io.github.jungm.crema.it.support.Findings;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deploys the coexistence WAR with {@code @LoginConfig(authMethod = "MP-JWT")} on its JAX-RS applications on every
 * Runtime, including those whose profile runs {@link SecurityCoexistenceIT} without it, and records whether the
 * Runtime accepts {@code @LoginConfig} next to a Jakarta Security mechanism.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class LoginConfigNextToMechanismIT {

    private static final String DEPLOYMENT = "login-config-next-to-mechanism";
    private static final FakeOidcProvider OIDC = FakeOidcProvider.start();
    private static final Findings FINDINGS = Findings.of(LoginConfigNextToMechanismIT.class);

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = DEPLOYMENT, managed = false, testable = false)
    static WebArchive deployment() {
        return CoexistenceWar.create(DEPLOYMENT, true, OIDC.issuer());
    }

    @AfterAll
    static void stopProvider() {
        OIDC.close();
    }

    @Test
    void recordWhetherLoginConfigDeploysNextToMechanism() {
        boolean deployed;
        String outcome;
        try {
            deployer.deploy(DEPLOYMENT);
            deployed = true;
            outcome = "deploys";
        } catch (Exception e) {
            // Arquillian rethrows the adapter's checked DeploymentException undeclared.
            deployed = false;
            outcome = "fails: " + rootMessage(e);
        }
        if (deployed) {
            deployer.undeploy(DEPLOYMENT);
        }
        FINDINGS.record("1 @LoginConfig + OIDC mechanism", outcome);
        assertEquals(CoexistenceWar.loginConfig(), deployed, outcome);
    }

    private static String rootMessage(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        String all = messages.toString();
        int code = all.indexOf("CWWKS");
        return (code >= 0 ? all.substring(code) : all).lines().findFirst().orElse("");
    }
}
