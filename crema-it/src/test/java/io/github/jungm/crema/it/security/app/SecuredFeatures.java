package io.github.jungm.crema.it.security.app;

import java.security.Principal;

import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tools of the protected default MCP Server.
 */
@ApplicationScoped
public class SecuredFeatures {

    @Tool(description = "Anyone with a valid token")
    @PermitAll
    public String everyone() {
        return "everyone";
    }

    @Tool(description = "Role user, from the McpApplication")
    public String users() {
        return "users";
    }

    @Tool(description = "Role admin")
    @RolesAllowed("admin")
    public String admins() {
        return "admins";
    }

    @Tool(description = "Who calls")
    public String whoami(McpCaller caller, Principal principal) {
        return caller.getName() + " " + caller.claims().get("email") + " " + (principal == caller);
    }
}
