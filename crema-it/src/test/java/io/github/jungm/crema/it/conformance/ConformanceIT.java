package io.github.jungm.crema.it.conformance;

import io.github.jungm.crema.it.conformance.app.ConformanceMcp;
import io.github.jungm.crema.it.mcp.CremaLibs;
import io.github.jungm.crema.it.support.Findings;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the official MCP conformance suite ({@code @modelcontextprotocol/conformance}, pinned in the POM) with
 * {@code --requirements 2026-07-28} against a WAR with exactly the fixtures of protocol-notes "Test tooling". The
 * run must exit with 0; the baseline {@code src/test/conformance/baseline-2026-07-28.yml} lists only what Crema
 * doesn't support by design. Needs Node.js 20 or newer ({@code npx}) on the build machine.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class ConformanceIT {

    private static final Findings FINDINGS = Findings.of(ConformanceIT.class);

    @ArquillianResource
    private URL base;

    @Deployment(testable = false)
    static WebArchive deployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "conformance.war")
                .addPackage(ConformanceMcp.class.getPackage())
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return CremaLibs.addTo(war);
    }

    @Test
    void officialSuitePasses() throws IOException, InterruptedException {
        String version = System.getProperty("crema.it.conformance.version");
        String baseline = System.getProperty("crema.it.conformance.baseline");
        Path output = Path.of(System.getProperty("crema.it.conformance.output", "target/conformance"),
                Findings.RUNTIME);
        Files.createDirectories(output);
        // dns-rebinding-protection requires a loopback host name in the URL.
        String path = base.getPath().endsWith("/") ? base.getPath() : base.getPath() + "/";
        String url = "http://localhost:" + base.getPort() + path + "mcp";
        List<String> command = List.of("npx", "-y", "@modelcontextprotocol/conformance@" + version, "server",
                "--url", url, "--requirements", "2026-07-28", "--expected-failures", baseline,
                "-o", output.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                System.out.println("CONFORMANCE " + line);
                lines.add(line);
            }
        }
        assertTrue(process.waitFor(20, TimeUnit.MINUTES), "conformance suite timed out");
        Files.write(output.resolve("output.txt"), lines);
        lines.subList(Math.max(0, lines.size() - 15), lines.size()).stream().filter(l -> !l.isBlank())
                .forEach(l -> FINDINGS.record("conformance", l.strip()));
        assertEquals(0, process.exitValue(), () -> "conformance suite failed:\n" + String.join("\n", lines));
    }
}
