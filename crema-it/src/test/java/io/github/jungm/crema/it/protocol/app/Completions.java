package io.github.jungm.crema.it.protocol.app;

import java.util.List;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.completion.CompleteArg;
import org.mcpjava.server.completion.CompletePrompt;
import org.mcpjava.server.completion.CompleteResourceTemplate;
import org.mcpjava.server.completion.CompletionContext;
import org.mcpjava.server.completion.CompletionResult;

/** Completion Methods for a Prompt and two Resource Templates. */
@ApplicationScoped
public class Completions {

    @CompletePrompt("review")
    public List<String> language(@CompleteArg(name = "language") String prefix) {
        return List.of("go", "java", "javascript", "kotlin").stream().filter(l -> l.startsWith(prefix)).toList();
    }

    @CompleteResourceTemplate("order")
    public CompletionResult orderId(String id) {
        return CompletionResult.builder().addValue(id + "1").addValue(id + "2").setTotal(2).setHasMore(false).build();
    }

    /** More values than the spec allows in one result. */
    @CompleteResourceTemplate("user_file")
    public List<String> file(String file, CompletionContext context) {
        String user = context.getArgument("user").orElse("nobody");
        return IntStream.range(0, 150).mapToObj(i -> user + "-" + file + i).toList();
    }
}
