package io.github.jungm.crema.internal.security;

import static io.github.jungm.crema.internal.security.Fixture.ENDPOINT;
import static io.github.jungm.crema.internal.security.Fixture.OPEN_ENDPOINT;
import static io.github.jungm.crema.internal.security.Fixture.OTHER_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jungm.crema.internal.http.HttpReply;
import io.github.jungm.crema.internal.json.Json;
import io.github.jungm.crema.internal.security.Fixture.Exchange;
import io.github.jungm.crema.internal.security.Fixture.RequestCaller;
import io.github.jungm.crema.testkit.FakeAuthorizationServer;

/**
 * Role enforcement and list filtering on protected and open MCP Servers, and the Protected Resource Metadata.
 */
class RolesTest {

    private static final String FORBIDDEN = "Bearer error=\"insufficient_scope\", resource_metadata=\"" + ENDPOINT
            + "/.well-known/oauth-protected-resource\"";

    private final FakeAuthorizationServer as = FakeAuthorizationServer.start();
    private Fixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.policy.close();
        }
        as.close();
    }

    private Fixture fixture(String rolesClaim) {
        fixture = new Fixture(Fixture.protection(as, "default", ENDPOINT, rolesClaim, false),
                Fixture.protection(as, "other", OTHER_ENDPOINT, rolesClaim, false));
        return fixture;
    }

    private RequestCaller user() {
        return RequestCaller.bearer(as.token(ENDPOINT));
    }

    private RequestCaller withGroups(String... groups) {
        return RequestCaller.bearer(as.token(ENDPOINT, c -> c.claim("groups", List.of(groups))));
    }

    @Test
    void rolesComeFromGroupsByDefault() {
        fixture("groups");
        assertEquals(200, fixture.callTool(fixture.protectedServer, user(), "users").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, user(), "admins").status());
        assertEquals(200, fixture.callTool(fixture.protectedServer, withGroups("admin"), "admins").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, withGroups("admin"), "users").status());
    }

    @Test
    void rolesComeFromKeycloakRealmAccess() {
        fixture("realm_access.roles");
        RequestCaller admin = RequestCaller.bearer(as.token(ENDPOINT,
                c -> c.claim("realm_access", Map.of("roles", List.of("admin", "offline_access")))));
        assertEquals(200, fixture.callTool(fixture.protectedServer, admin, "admins").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, user(), "users").status(),
                "groups isn't the roles claim");
    }

    @Test
    void rolesComeFromEntraRoles() {
        fixture("roles");
        RequestCaller admin = RequestCaller.bearer(as.token(ENDPOINT,
                c -> c.claim("roles", List.of("admin"))));
        assertEquals(200, fixture.callTool(fixture.protectedServer, admin, "admins").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, admin, "users").status());
    }

    @Test
    void forbiddenCallsGetInsufficientScope() {
        fixture("groups");
        for (Exchange exchange : List.of(
                fixture.callTool(fixture.protectedServer, user(), "admins"),
                fixture.callTool(fixture.protectedServer, user(), "nobody"),
                fixture.call(fixture.protectedServer, user(), "prompts/get", ",\"name\":\"adminPrompt\"",
                        "adminPrompt"),
                fixture.call(fixture.protectedServer, user(), "resources/read", ",\"uri\":\"test://admin\"",
                        "test://admin"),
                fixture.call(fixture.protectedServer, user(), "completion/complete",
                        ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\"adminPrompt\"},"
                                + "\"argument\":{\"name\":\"topic\",\"value\":\"x\"}", null))) {
            assertEquals(403, exchange.status());
            assertEquals(FORBIDDEN, exchange.challenge());
            assertNull(exchange.message());
        }
        assertEquals(200, fixture.call(fixture.protectedServer, withGroups("admin", "user"), "completion/complete",
                ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\"adminPrompt\"},"
                        + "\"argument\":{\"name\":\"topic\",\"value\":\"x\"}", null).status());
    }

    @Test
    void anyAuthenticatedCallerMatchesDoubleStar() {
        fixture("groups");
        RequestCaller noRoles = withGroups();
        assertEquals(200, fixture.callTool(fixture.protectedServer, noRoles, "authenticated").status());
        assertEquals(200, fixture.callTool(fixture.protectedServer, noRoles, "everyone").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, noRoles, "users").status());
        assertEquals(200, fixture.callTool(fixture.otherServer,
                RequestCaller.bearer(as.token(OTHER_ENDPOINT, c -> c.claim("groups", List.of()))),
                "other").status());
    }

    @Test
    void methodBeatsClassBeatsApplication() {
        fixture("groups");
        assertEquals(403, fixture.callTool(fixture.protectedServer, user(), "classLevel").status());
        assertEquals(200, fixture.callTool(fixture.protectedServer, withGroups("admin"), "classLevel").status());
        assertEquals(200, fixture.callTool(fixture.protectedServer, withGroups(), "methodLevel").status());
    }

    @Test
    void listsHoldOnlyPermittedFeatures() {
        fixture("groups");
        Exchange user = fixture.listTools(fixture.protectedServer, user());
        assertEquals(Set.of("everyone", "users", "authenticated", "whoami", "methodLevel"), user.names("tools"));
        assertEquals("private", user.result().getString("cacheScope"));
        assertEquals(Set.of("everyone", "users", "admins", "adminProgress", "authenticated", "whoami", "classLevel",
                "methodLevel"),
                fixture.listTools(fixture.protectedServer, withGroups("user", "admin")).names("tools"));
        assertEquals(Set.of(), fixture.call(fixture.protectedServer, user(), "prompts/list", "", null)
                .names("prompts"));
        assertEquals(Set.of("adminPrompt"), fixture.call(fixture.protectedServer, withGroups("admin"),
                "prompts/list", "", null).names("prompts"));
        assertEquals(Set.of(), fixture.call(fixture.protectedServer, user(), "resources/list", "", null)
                .names("resources"));
    }

    @Test
    void openServerUsesTheRuntimeCaller() {
        fixture("groups");
        RequestCaller anonymous = RequestCaller.anonymous();
        Exchange list = fixture.listTools(fixture.openServer, anonymous);
        assertEquals(Set.of("free", "whoami"), list.names("tools"));
        assertEquals("private", list.result().getString("cacheScope"), "the open server has role restrictions");
        Exchange forbidden = fixture.callTool(fixture.openServer, anonymous, "admin");
        assertEquals(403, forbidden.status());
        assertNull(forbidden.challenge());
        assertEquals("anonymous", fixture.callTool(fixture.openServer, anonymous, "whoami").text());

        Principal bob = () -> "bob";
        RequestCaller admin = new RequestCaller(bob, Set.of("admin"));
        assertEquals(Set.of("free", "admin", "authenticated", "whoami"),
                fixture.listTools(fixture.openServer, admin).names("tools"));
        assertEquals(200, fixture.callTool(fixture.openServer, admin, "admin").status());
        assertEquals("bob {} bob", fixture.callTool(fixture.openServer, admin, "whoami").text());
        assertEquals(200, fixture.callTool(fixture.openServer, new RequestCaller(bob, Set.of()),
                "authenticated").status());
    }

    @Test
    void cacheScopeIsPrivateOnlyIfTheResultMayDependOnTheCaller() {
        fixture("groups");
        String read = ",\"uri\":\"test://plain\"";
        RequestCaller anonymous = RequestCaller.anonymous();
        assertEquals("public", fixture.listTools(fixture.plainServer, anonymous).result().getString("cacheScope"));
        assertEquals("public", fixture.call(fixture.plainServer, anonymous, "resources/read", read, "test://plain")
                .result().getString("cacheScope"));
        assertEquals("public", fixture.call(fixture.plainServer, anonymous, "server/discover", "", null).result()
                .getString("cacheScope"));

        RequestCaller bob = new RequestCaller(() -> "bob", Set.of());
        assertEquals("private", fixture.listTools(fixture.plainServer, bob).result().getString("cacheScope"),
                "the Runtime authenticated the caller");
        assertEquals("private", fixture.call(fixture.plainServer, bob, "resources/read", read, "test://plain")
                .result().getString("cacheScope"));

        assertEquals("private", fixture.listTools(fixture.openServer, anonymous).result().getString("cacheScope"),
                "Features are restricted to roles");
        assertEquals("private", fixture.listTools(fixture.protectedServer, user()).result().getString("cacheScope"),
                "the MCP Server is protected");
    }

    @Test
    void openServerIgnoresBearerTokens() {
        fixture("groups");
        Exchange exchange = fixture.callTool(fixture.openServer,
                RequestCaller.bearer(as.token(OPEN_ENDPOINT, c -> c.claim("groups", List.of("admin")))),
                "admin");
        assertEquals(403, exchange.status());
    }

    @Test
    void protectedServerIgnoresTheRuntimeCaller() {
        fixture("groups");
        RequestCaller runtimeAdmin = new RequestCaller(() -> "bob", Set.of("user", "admin"));
        assertEquals(401, fixture.callTool(fixture.protectedServer, runtimeAdmin, "users").status());
        assertEquals(false, fixture.policy.permits(fixture.protectedServer,
                fixture.protectedServer.tool("everyone").orElseThrow().method(), runtimeAdmin));
    }

    @Test
    void protectedResourceMetadata() {
        fixture("groups");
        HttpReply metadata = fixture.transport.resourceMetadata(fixture.protectedServer, "GET");
        assertEquals(200, metadata.status());
        assertEquals(Json.parse("{\"resource\":\"" + ENDPOINT + "\",\"authorization_servers\":[\"" + as.issuer()
                + "\"],\"bearer_methods_supported\":[\"header\"]}"), Json.parse(metadata.body()));
        HttpReply post = fixture.transport.resourceMetadata(fixture.protectedServer, "POST");
        assertEquals(405, post.status());
        assertEquals("GET", post.headers().get("Allow"));
        assertEquals(404, fixture.transport.resourceMetadata(fixture.openServer, "GET").status());
    }

    @Test
    void protectedResourceMetadataListsTheScopes() {
        fixture = new Fixture(Fixture.protection(as, "default", ENDPOINT, "groups", false, List.of("crema", "openid")),
                Fixture.protection(as, "other", OTHER_ENDPOINT, "groups", false));
        HttpReply metadata = fixture.transport.resourceMetadata(fixture.protectedServer, "GET");
        assertEquals(Json.parse("{\"resource\":\"" + ENDPOINT + "\",\"authorization_servers\":[\"" + as.issuer()
                + "\"],\"bearer_methods_supported\":[\"header\"],\"scopes_supported\":[\"crema\",\"openid\"]}"),
                Json.parse(metadata.body()));
        assertEquals(FORBIDDEN, fixture.callTool(fixture.protectedServer, user(), "admins").challenge());
    }

    @Test
    void hiddenProgressToolIsForbiddenBeforeAnyStreamStarts() {
        fixture("groups");
        String progressToken = ",\"progressToken\":\"p\"";
        Exchange forbidden = fixture.call(fixture.protectedServer, user(), "tools/call",
                ",\"name\":\"adminProgress\"", "adminProgress", progressToken);
        assertEquals(403, forbidden.status());
        assertEquals(false, forbidden.streamed());
        assertEquals(FORBIDDEN, forbidden.challenge());
        assertNull(forbidden.message());

        Exchange streamed = fixture.call(fixture.protectedServer, withGroups("admin"), "tools/call",
                ",\"name\":\"adminProgress\"", "adminProgress", progressToken);
        assertEquals(true, streamed.streamed());
        assertEquals("progress", streamed.text());
    }
}
