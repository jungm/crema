package io.github.jungm.crema.it.security.app;

import java.security.Principal;
import java.util.List;

import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.tools.Tool;

import io.github.jungm.crema.McpCaller;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Features of the protected default MCP Server.
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

    @Tool(description = "Role admin, reports progress")
    @RolesAllowed("admin")
    public String adminProgress(Progress progress) {
        if (progress.token().isPresent()) {
            progress.notificationBuilder().setProgress(1).setTotal(1).build().sendAndForget();
        }
        return "progress";
    }

    @Tool(description = "Who calls")
    public String whoami(McpCaller caller, Principal principal) {
        return caller.getName() + " " + caller.claims().get("email") + " " + (principal == caller);
    }

    @Resource(uri = "secure://admin", description = "Role admin")
    @RolesAllowed("admin")
    public String adminResource() {
        return "admin resource";
    }

    @Prompt(description = "Role admin")
    @RolesAllowed("admin")
    public String adminPrompt(String topic) {
        return "admin prompt about " + topic;
    }

    @CompletePrompt("adminPrompt")
    public List<String> completeTopic(String topic) {
        return List.of(topic + "1");
    }
}
