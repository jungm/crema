package io.github.jungm.crema.it.coexistence.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.tools.Tool;

/** Tools whose results would change if the application's providers applied to MCP traffic. */
@ApplicationScoped
public class StatusTools {

    @Tool(description = "The shop status", structuredContent = true)
    public ApiRoot.Status status() {
        return new ApiRoot.Status("shop", 3);
    }

    @Tool(description = "Reports progress")
    public String progress(Progress progress) {
        if (progress.token().isPresent()) {
            progress.notificationBuilder().setProgress(1).setTotal(1).build().sendAndForget();
        }
        return "done";
    }

    @Tool(description = "Fails")
    public String fail() {
        throw new IllegalStateException("fails");
    }

    @Tool(description = "Fails with an Error")
    public String fatal() {
        throw new LinkageError("fatal");
    }
}
