package io.github.jungm.crema.internal.json;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MCP format of {@code _meta} keys, applied to every key an application supplies: {@code @MetaField}
 * annotations at deployment, and the {@code _meta} entries of values built through the SPI builders and of
 * progress notifications at call time.
 * <p>
 * A key is an optional prefix followed by a name. The prefix is a series of dot-separated labels followed by
 * {@code /}; each label starts with a letter and ends with a letter or digit, with letters, digits and {@code -}
 * in between. The name is empty or starts and ends with a letter or digit, with letters, digits, {@code -},
 * {@code _} and {@code .} in between. Prefixes reserved for MCP (see {@link #isReserved(String)}) are rejected
 * everywhere, since only the protocol itself may use them.
 */
public final class MetaKeys {

    private static final String LABEL = "[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?";
    private static final Pattern PREFIX = Pattern.compile(LABEL + "(?:\\." + LABEL + ")*/");
    private static final Pattern NAME = Pattern.compile("(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?");
    private static final Set<String> RESERVED_LABELS = Set.of("modelcontextprotocol", "mcp");

    private MetaKeys() {
    }

    /**
     * Checks a complete key.
     *
     * @return the key
     * @throws IllegalArgumentException if the key's prefix or name is malformed, or its prefix is reserved
     */
    public static String requireValid(String key) {
        Objects.requireNonNull(key, "key");
        int slash = key.lastIndexOf('/');
        try {
            checkPrefix(key.substring(0, slash + 1));
            checkName(key.substring(slash + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid _meta key '" + key + "': " + e.getMessage());
        }
        return key;
    }

    /**
     * Checks a prefix; the empty prefix is valid.
     *
     * @throws IllegalArgumentException if the prefix is malformed or reserved
     */
    public static void checkPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return;
        }
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("prefix '" + prefix
                    + "' must be dot-separated labels followed by '/', e.g. 'example.com/'");
        }
        if (isReserved(prefix)) {
            throw new IllegalArgumentException("prefix '" + prefix + "' is reserved for MCP");
        }
    }

    /**
     * Checks a name; the empty name is valid.
     *
     * @throws IllegalArgumentException if the name is malformed
     */
    public static void checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name '" + name
                    + "' must begin and end with a letter or digit and contain only letters, digits, '-', '_' "
                    + "and '.'");
        }
    }

    /**
     * Whether a well-formed prefix is reserved for MCP: {@code modelcontextprotocol} or {@code mcp} is its second
     * label (as in {@code io.modelcontextprotocol/}) or any label but its last (as in {@code tools.mcp.com/}).
     */
    public static boolean isReserved(String prefix) {
        List<String> labels = Arrays.asList(prefix.substring(0, prefix.length() - 1).split("\\."));
        if (labels.size() >= 2 && RESERVED_LABELS.contains(labels.get(1))) {
            return true;
        }
        return labels.subList(0, labels.size() - 1).stream().anyMatch(RESERVED_LABELS::contains);
    }
}
