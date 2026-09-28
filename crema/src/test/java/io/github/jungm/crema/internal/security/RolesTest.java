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
import io.github.jungm.crema.internal.protocol.Json;
import io.github.jungm.crema.internal.security.Fixture.Exchange;
import io.github.jungm.crema.internal.security.Fixture.RequestCaller;

/**
 * Role enforcement and list filtering on protected and open MCP Servers, and the Protected Resource Metadata.
 */
class RolesTest {

    private static final String FORBIDDEN = "Bearer error=\"insufficient_scope\", resource_metadata=\"" + ENDPOINT
            + "/.well-known/oauth-protected-resource\"";

    private final FakeAuthorizationServer as = new FakeAuthorizationServer();
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
        return RequestCaller.bearer(ENDPOINT, as.token(ENDPOINT));
    }

    private RequestCaller withGroups(String... groups) {
        return RequestCaller.bearer(ENDPOINT, as.token(ENDPOINT, c -> c.claim("groups", List.of(groups))));
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
        RequestCaller admin = RequestCaller.bearer(ENDPOINT, as.token(ENDPOINT,
                c -> c.claim("realm_access", Map.of("roles", List.of("admin", "offline_access")))));
        assertEquals(200, fixture.callTool(fixture.protectedServer, admin, "admins").status());
        assertEquals(403, fixture.callTool(fixture.protectedServer, user(), "users").status(),
                "groups isn't the roles claim");
    }

    @Test
    void rolesComeFromEntraRoles() {
        fixture("roles");
        RequestCaller admin = RequestCaller.bearer(ENDPOINT, as.token(ENDPOINT,
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
                RequestCaller.bearer(OTHER_ENDPOINT, as.token(OTHER_ENDPOINT, c -> c.claim("groups", List.of()))),
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
        assertEquals(Set.of("everyone", "users", "admins", "authenticated", "whoami", "classLevel", "methodLevel"),
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
        RequestCaller anonymous = RequestCaller.anonymous(OPEN_ENDPOINT);
        Exchange list = fixture.listTools(fixture.openServer, anonymous);
        assertEquals(Set.of("free", "whoami"), list.names("tools"));
        assertEquals("private", list.result().getString("cacheScope"), "the open server has role restrictions");
        Exchange forbidden = fixture.callTool(fixture.openServer, anonymous, "admin");
        assertEquals(403, forbidden.status());
        assertNull(forbidden.challenge());
        assertEquals("anonymous", fixture.callTool(fixture.openServer, anonymous, "whoami").text());

        Principal bob = () -> "bob";
        RequestCaller admin = new RequestCaller(OPEN_ENDPOINT, bob, Set.of("admin"));
        assertEquals(Set.of("free", "admin", "authenticated", "whoami"),
                fixture.listTools(fixture.openServer, admin).names("tools"));
        assertEquals(200, fixture.callTool(fixture.openServer, admin, "admin").status());
        assertEquals("bob {} bob", fixture.callTool(fixture.openServer, admin, "whoami").text());
        assertEquals(200, fixture.callTool(fixture.openServer, new RequestCaller(OPEN_ENDPOINT, bob, Set.of()),
                "authenticated").status());
    }

    @Test
    void openServerIgnoresBearerTokens() {
        fixture("groups");
        Exchange exchange = fixture.callTool(fixture.openServer,
                RequestCaller.bearer(OPEN_ENDPOINT, as.token(OPEN_ENDPOINT, c -> c.claim("groups", List.of("admin")))),
                "admin");
        assertEquals(403, exchange.status());
    }

    @Test
    void protectedServerIgnoresTheRuntimeCaller() {
        fixture("groups");
        RequestCaller runtimeAdmin = new RequestCaller(ENDPOINT, () -> "bob", Set.of("user", "admin"));
        assertEquals(401, fixture.callTool(fixture.protectedServer, runtimeAdmin, "users").status());
        assertEquals(false, fixture.policy.permits(fixture.protectedServer,
                fixture.protectedServer.tool("everyone").orElseThrow(), runtimeAdmin));
    }

    @Test
    void protectedResourceMetadata() {
        fixture("groups");
        HttpReply metadata = fixture.transport.resourceMetadata(fixture.protectedServer,
                RequestCaller.anonymous("https://ignored.test/mcp"));
        assertEquals(200, metadata.status());
        assertEquals(Json.parse("{\"resource\":\"" + ENDPOINT + "\",\"authorization_servers\":[\"" + as.issuer()
                + "\"],\"bearer_methods_supported\":[\"header\"]}"), Json.parse(metadata.body()));
        assertEquals(404, fixture.transport.resourceMetadata(fixture.openServer,
                RequestCaller.anonymous(OPEN_ENDPOINT)).status());
    }

    @Test
    void protectedResourceMetadataWithDerivedResource() {
        fixture = new Fixture(Fixture.protection(as, "default", null, "groups", false),
                Fixture.protection(as, "other", null, "groups", false));
        HttpReply metadata = fixture.transport.resourceMetadata(fixture.protectedServer,
                RequestCaller.anonymous("https://public.test/ctx/mcp"));
        assertEquals("https://public.test/ctx/mcp",
                Json.parse(metadata.body()).asJsonObject().getString("resource"));
    }

    @Test
    void securitySchemeOfTheTokenCaller() {
        fixture("groups");
        Caller admitted = ((io.github.jungm.crema.internal.http.McpTransport.Admitted) fixture.transport
                .screen(fixture.protectedServer, null, withGroups("user", "admin"))).caller();
        jakarta.ws.rs.core.SecurityContext context = TokenSecurityContext.replacing(null, admitted).orElseThrow();
        assertEquals("alice", context.getUserPrincipal().getName());
        assertEquals(true, context.isUserInRole("admin"));
        assertEquals(false, context.isUserInRole("other"));
        assertEquals("Bearer", context.getAuthenticationScheme());
        assertEquals(false, context.isSecure());
        assertEquals(admitted, TokenSecurityContext.caller(context).orElseThrow());
        assertEquals(true, TokenSecurityContext.replacing(null, RequestCaller.anonymous(ENDPOINT)).isEmpty());
        assertEquals(new io.github.jungm.crema.internal.http.McpTransport.Admitted(admitted),
                fixture.transport.screen(fixture.protectedServer, null, admitted), "admitted callers pass again");
    }
}
