package io.github.jungm.crema.internal.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import io.github.jungm.crema.internal.config.ConfigLookup;
import io.github.jungm.crema.internal.config.McpServerSettings;

/**
 * How an MCP Server authenticates its callers, from its {@code authenticator} key: {@code oauth}, {@code basic} or
 * {@code bean}. A protected MCP Server (one whose {@code McpApplication} subclass carries {@code @RolesAllowed} or
 * {@code @DenyAll}) must set it; an open one may set {@code basic} or {@code bean}, and without it uses an
 * {@code McpAuthenticator} bean if one is bound to it, else the Runtime's caller.
 */
public sealed interface AuthenticatorSetting {

    String KEY = "authenticator";
    String USERS = "users";

    /**
     * No {@code authenticator} on an open MCP Server.
     */
    record Unset() implements AuthenticatorSetting {
    }

    /**
     * OAuth bearer tokens.
     */
    record OAuth(Protection protection) implements AuthenticatorSetting {
    }

    /**
     * HTTP Basic authentication against the configured users.
     */
    record Basic(Map<String, BasicMechanism.User> users) implements AuthenticatorSetting {

        public Basic {
            users = Map.copyOf(users);
        }
    }

    /**
     * The {@code McpAuthenticator} bean bound to the MCP Server.
     */
    record Bean() implements AuthenticatorSetting {
    }

    /**
     * The characters a user name must not contain: the {@code :} that ends it in Basic credentials, and those that
     * separate the names in {@code users}.
     */
    Pattern USER_NAME = Pattern.compile("[^:,\\s]+");

    /**
     * Resolves the setting of the MCP Server that an {@code McpApplication} subclass declares.
     *
     * @param problems collects deployment problems
     * @return the setting; meaningless if problems were added
     */
    static AuthenticatorSetting resolve(Class<?> application, McpServerSettings settings, ConfigLookup config,
            List<String> problems) {
        String prefix = McpServerSettings.keyPrefix(settings.name());
        String where = "McpApplication " + application.getName() + " (MCP Server '" + settings.wireName() + "')";
        boolean isProtected = AccessRule.ofApplication(application, problems).map(AccessRule::restricts)
                .orElse(false);
        Optional<String> value = config.get(prefix + KEY).map(String::trim);
        AuthenticatorSetting setting;
        if (value.isEmpty()) {
            if (isProtected) {
                problems.add(where + " is protected by @RolesAllowed or @DenyAll, but " + prefix + KEY
                        + " isn't set; set it to oauth, basic or bean" + (config.isMicroProfile() ? ""
                                : " with MicroProfile Config, which isn't available"));
            }
            setting = new Unset();
        } else {
            switch (value.get()) {
            case "oauth":
                if (!isProtected) {
                    problems.add(where + ": " + prefix + KEY + " is oauth, which needs a protected MCP Server; put "
                            + "@RolesAllowed or @DenyAll on the McpApplication");
                    setting = new Unset();
                } else {
                    setting = Protection.resolve(settings, config, where, problems)
                            .<AuthenticatorSetting>map(OAuth::new).orElseGet(Unset::new);
                }
                break;
            case "basic":
                setting = new Basic(users(prefix, where, config, problems));
                break;
            case "bean":
                setting = new Bean();
                break;
            default:
                problems.add(where + ": " + prefix + KEY + " must be oauth, basic or bean, but is '" + value.get()
                        + "'");
                setting = new Unset();
            }
        }
        warnUnused(setting, prefix, where, config);
        return setting;
    }

    /**
     * Reads the users of Basic authentication: {@code users} names them, and each has a {@code password} and
     * optionally comma-separated {@code roles} under {@code users.<name>.}.
     */
    private static Map<String, BasicMechanism.User> users(String prefix, String where, ConfigLookup config,
            List<String> problems) {
        Map<String, BasicMechanism.User> users = new LinkedHashMap<>();
        int before = problems.size();
        Optional<String> names = config.get(prefix + USERS);
        if (names.isEmpty()) {
            problems.add(where + " uses Basic authentication, but " + prefix + USERS + " isn't set; set it to the "
                    + "comma-separated names of the users");
            return users;
        }
        for (String entry : names.get().split(",")) {
            String name = entry.strip();
            if (name.isEmpty() || users.containsKey(name)) {
                continue;
            }
            if (!USER_NAME.matcher(name).matches()) {
                problems.add(where + ": " + prefix + USERS + " contains '" + name + "', but user names must not "
                        + "contain ':' or whitespace");
                continue;
            }
            String userPrefix = prefix + USERS + "." + name + ".";
            Optional<String> password = config.get(userPrefix + "password");
            if (password.isEmpty()) {
                problems.add(where + ": " + userPrefix + "password isn't set");
                continue;
            }
            Set<String> roles = new LinkedHashSet<>();
            config.get(userPrefix + "roles").ifPresent(value -> {
                for (String role : value.split(",")) {
                    if (!role.isBlank()) {
                        roles.add(role.strip());
                    }
                }
            });
            users.put(name, new BasicMechanism.User(password.get(), roles));
        }
        if (users.isEmpty() && problems.size() == before) {
            problems.add(where + ": " + prefix + USERS + " names no users");
        }
        return users;
    }

    /**
     * Warns about keys of another authenticator, which have no effect.
     */
    private static void warnUnused(AuthenticatorSetting setting, String prefix, String where, ConfigLookup config) {
        Logger log = Logger.getLogger(AuthenticatorSetting.class.getName());
        if (!(setting instanceof OAuth)) {
            config.get(prefix + Protection.ISSUER).ifPresent(issuer -> log.warning(where + ": " + prefix
                    + Protection.ISSUER + " has no effect, because " + prefix + KEY + " isn't oauth"));
        }
        if (!(setting instanceof Basic)) {
            config.get(prefix + USERS).ifPresent(users -> log.warning(where + ": " + prefix + USERS
                    + " has no effect, because " + prefix + KEY + " isn't basic"));
        }
    }
}
