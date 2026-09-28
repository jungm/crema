package io.github.jungm.crema.internal.security;

import java.security.Principal;
import java.util.List;

/**
 * Who sends the current request. Some Runtimes throw from {@link #principal()} and {@link #isUserInRole(String)}
 * when the request carries no credentials, so an {@link AccessPolicy} checks {@link #header(String)} first.
 */
public interface Caller {

    Caller ANONYMOUS = new Caller() {
        @Override
        public List<String> header(String name) {
            return List.of();
        }

        @Override
        public Principal principal() {
            return null;
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }
    };

    /**
     * The values of a request header, by case-insensitive name; empty when absent.
     */
    List<String> header(String name);

    /**
     * The principal the Runtime authenticated, or {@code null}.
     */
    Principal principal();

    boolean isUserInRole(String role);
}
