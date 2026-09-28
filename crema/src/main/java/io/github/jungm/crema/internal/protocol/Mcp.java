package io.github.jungm.crema.internal.protocol;

import java.util.Optional;

/**
 * Names that MCP revision {@value #PROTOCOL_VERSION} defines: the protocol version, method names and the
 * {@code _meta} keys Crema reads or writes.
 */
public final class Mcp {

    /** The one protocol revision Crema implements. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** The prefix of the {@code _meta} keys that MCP itself defines. */
    public static final String META_PREFIX = "io.modelcontextprotocol/";
    /** The request {@code _meta} key holding the protocol version. */
    public static final String META_PROTOCOL_VERSION = META_PREFIX + "protocolVersion";
    /** The request {@code _meta} key holding the client capabilities. */
    public static final String META_CLIENT_CAPABILITIES = META_PREFIX + "clientCapabilities";
    /** The request {@code _meta} key holding the client's {@code Implementation}. */
    public static final String META_CLIENT_INFO = META_PREFIX + "clientInfo";
    /** The result {@code _meta} key holding the server's {@code Implementation}. */
    public static final String META_SERVER_INFO = META_PREFIX + "serverInfo";
    /**
     * The request {@code _meta} key holding the progress token, which is also the parameter of
     * {@code notifications/progress} that echoes it.
     */
    public static final String PROGRESS_TOKEN = "progressToken";

    public static final String SERVER_DISCOVER = "server/discover";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";
    public static final String RESOURCES_LIST = "resources/list";
    public static final String RESOURCES_READ = "resources/read";
    public static final String RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String PROMPTS_LIST = "prompts/list";
    public static final String PROMPTS_GET = "prompts/get";
    public static final String COMPLETION_COMPLETE = "completion/complete";
    public static final String NOTIFICATIONS_PROGRESS = "notifications/progress";

    private Mcp() {
    }

    /**
     * The parameter that names the target of a method, which the {@code Mcp-Name} header repeats: {@code uri}
     * for {@code resources/read}, {@code name} for {@code tools/call} and {@code prompts/get}, and empty for
     * all other methods.
     */
    public static Optional<String> nameParam(String method) {
        switch (method) {
            case TOOLS_CALL:
            case PROMPTS_GET:
                return Optional.of("name");
            case RESOURCES_READ:
                return Optional.of("uri");
            default:
                return Optional.empty();
        }
    }
}
