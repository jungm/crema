package io.github.jungm.crema.it.deployment;

import io.github.jungm.crema.it.deployment.app.BadPrompt;
import io.github.jungm.crema.it.deployment.app.BadTemplate;
import io.github.jungm.crema.it.deployment.app.DefaultMcp;
import io.github.jungm.crema.it.deployment.app.DuplicateA;
import io.github.jungm.crema.it.deployment.app.DuplicateB;
import io.github.jungm.crema.it.deployment.app.OverridingMcp;
import io.github.jungm.crema.it.deployment.app.TwinDefaultMcp;
import io.github.jungm.crema.it.deployment.app.UndeclaredServer;
import io.github.jungm.crema.it.deployment.app.ValidTools;
import io.github.jungm.crema.it.mcp.CremaLibs;
import io.github.jungm.crema.it.support.Findings;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WARs with invalid MCP Servers or Features fail to deploy on every Runtime (design §3 and §6). A valid WAR built
 * the same way deploys, so the failures come from the flaws.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class DeploymentFailureIT {

    private static final Findings FINDINGS = Findings.of(DeploymentFailureIT.class);

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = "valid", managed = false, testable = false)
    static WebArchive valid() {
        return war("valid");
    }

    @Deployment(name = "duplicate-names", managed = false, testable = false)
    static WebArchive duplicateNames() {
        return war("duplicate-names", DuplicateA.class, DuplicateB.class);
    }

    @Deployment(name = "undeclared-server", managed = false, testable = false)
    static WebArchive undeclaredServer() {
        return war("undeclared-server", UndeclaredServer.class);
    }

    @Deployment(name = "overriding-application", managed = false, testable = false)
    static WebArchive overridingApplication() {
        return war("overriding-application", OverridingMcp.class);
    }

    @Deployment(name = "duplicate-servers", managed = false, testable = false)
    static WebArchive duplicateServers() {
        return war("duplicate-servers", TwinDefaultMcp.class);
    }

    @Deployment(name = "bad-prompt", managed = false, testable = false)
    static WebArchive badPrompt() {
        return war("bad-prompt", BadPrompt.class);
    }

    @Deployment(name = "bad-template", managed = false, testable = false)
    static WebArchive badTemplate() {
        return war("bad-template", BadTemplate.class);
    }

    private static WebArchive war(String name, Class<?>... extra) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name + ".war")
                .addClasses(DefaultMcp.class, ValidTools.class)
                .addClasses(extra)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return CremaLibs.addTo(war);
    }

    @Test
    void validWarDeploys() {
        deployer.deploy("valid");
        deployer.undeploy("valid");
    }

    @Test
    void duplicateFeatureNamesFail() {
        assertDeploymentFails("duplicate-names");
    }

    @Test
    void featureOfUndeclaredMcpServerFails() {
        assertDeploymentFails("undeclared-server");
    }

    @Test
    void mcpApplicationOverridingGetClassesFails() {
        assertDeploymentFails("overriding-application");
    }

    @Test
    void twoMcpApplicationsForOneMcpServerFail() {
        assertDeploymentFails("duplicate-servers");
    }

    @Test
    void unsupportedPromptReturnTypeFails() {
        assertDeploymentFails("bad-prompt");
    }

    @Test
    void templateVariablesNotMatchingParametersFail() {
        assertDeploymentFails("bad-template");
    }

    private void assertDeploymentFails(String name) {
        Exception failure = assertThrows(Exception.class, () -> deployer.deploy(name), name + " must not deploy");
        FINDINGS.record(name, "deployment fails: " + rootMessage(failure));
        try {
            deployer.undeploy(name);
        } catch (RuntimeException e) {
            // nothing left to undeploy
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
        return t.getClass().getSimpleName() + ": " + (message.length() > 300 ? message.substring(0, 300) + "…"
                : message);
    }
}
