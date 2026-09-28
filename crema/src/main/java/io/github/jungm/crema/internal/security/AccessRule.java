package io.github.jungm.crema.internal.security;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.crema.McpApplication;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

/**
 * Who may use a Feature or Completion Method, from {@code @DenyAll}, {@code @PermitAll} or {@code @RolesAllowed}.
 * The first of the method, its declaring class and the {@code McpApplication} subclass that carries one of them
 * decides; with none, everyone may. The role {@code **} stands for any authenticated caller.
 *
 * @param roles the allowed roles; empty for {@link Kind#PERMIT_ALL} and {@link Kind#DENY_ALL}
 */
record AccessRule(Kind kind, Set<String> roles) {

    static final String ANY_AUTHENTICATED = "**";
    static final AccessRule PERMIT_ALL = new AccessRule(Kind.PERMIT_ALL, Set.of());
    static final AccessRule DENY_ALL = new AccessRule(Kind.DENY_ALL, Set.of());

    private static final Logger LOG = Logger.getLogger(AccessRule.class.getName());

    enum Kind {
        PERMIT_ALL, DENY_ALL, ROLES
    }

    AccessRule {
        roles = Set.copyOf(roles);
    }

    /**
     * The rule of a Feature or Completion Method.
     *
     * @param application the rule of the {@code McpApplication} subclass, if it declares one
     * @param problems collects conflicting annotations
     */
    static AccessRule of(Method method, Optional<AccessRule> application, List<String> problems) {
        return declaredOn(method, "method " + method, problems)
                .or(() -> declaredOn(method.getDeclaringClass(), "class " + method.getDeclaringClass().getName(),
                        problems))
                .or(() -> application)
                .orElse(PERMIT_ALL);
    }

    /**
     * The rule an {@code McpApplication} subclass declares, on itself or on a superclass below
     * {@code McpApplication}.
     */
    static Optional<AccessRule> ofApplication(Class<?> application, List<String> problems) {
        for (Class<?> type = application; type != null && type != McpApplication.class
                && type != Object.class; type = type.getSuperclass()) {
            Optional<AccessRule> rule = declaredOn(type, "McpApplication " + type.getName(), problems);
            if (rule.isPresent()) {
                return rule;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a rule makes the MCP Server protected: {@code @RolesAllowed} or {@code @DenyAll}.
     */
    boolean restricts() {
        return kind != Kind.PERMIT_ALL;
    }

    boolean permits(Caller caller) {
        switch (kind) {
            case PERMIT_ALL:
                return true;
            case DENY_ALL:
                return false;
            default:
                if (CallerPrincipal.safely(caller::principal) == null) {
                    return false;
                }
                if (roles.contains(ANY_AUTHENTICATED)) {
                    return true;
                }
                for (String role : roles) {
                    if (isUserInRole(caller, role)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isUserInRole(Caller caller, String role) {
        try {
            return caller.isUserInRole(role);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "The Runtime couldn't tell the caller's roles; treating it as having none", e);
            return false;
        }
    }

    private static Optional<AccessRule> declaredOn(AnnotatedElement element, String where, List<String> problems) {
        List<AccessRule> rules = new ArrayList<>();
        if (element.isAnnotationPresent(DenyAll.class)) {
            rules.add(DENY_ALL);
        }
        if (element.isAnnotationPresent(PermitAll.class)) {
            rules.add(PERMIT_ALL);
        }
        RolesAllowed rolesAllowed = element.getAnnotation(RolesAllowed.class);
        if (rolesAllowed != null) {
            rules.add(new AccessRule(Kind.ROLES, new HashSet<>(Arrays.asList(rolesAllowed.value()))));
        }
        if (rules.size() > 1) {
            problems.add(where + ": carries more than one of @DenyAll, @PermitAll and @RolesAllowed");
        }
        return rules.stream().findFirst();
    }
}
