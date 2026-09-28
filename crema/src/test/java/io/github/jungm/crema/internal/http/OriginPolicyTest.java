package io.github.jungm.crema.internal.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginPolicyTest {

    private static final OriginPolicy DEFAULT = new OriginPolicy(List.of());
    private static final OriginPolicy CONFIGURED = new OriginPolicy(List.of("https://app.example.com",
            "http://intranet:8080/"));
    private static final OriginPolicy ANY = new OriginPolicy(List.of("*"));

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "http://localhost:8080", "https://localhost:8443",
            "http://127.0.0.1:9000", "http://[::1]", "https://[::1]:8443", "HTTP://LOCALHOST:8080",
            "http://localhost/"})
    void loopbackOriginsPass(String origin) {
        assertTrue(DEFAULT.permits(origin));
        assertTrue(CONFIGURED.permits(origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example.com", "http://evil.example.com:8080", "null",
            "http://localhost.evil.com", "http://127.0.0.1.nip.io", "ftp://localhost", "file://localhost",
            "http://user@localhost", "http://localhost/path", "not a url", "http://app.example.com",
            "https://app.example.com.evil.com"})
    void otherOriginsFail(String origin) {
        assertFalse(DEFAULT.permits(origin));
        assertFalse(CONFIGURED.permits(origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com", "https://APP.example.com/", "http://intranet:8080"})
    void configuredOriginsPass(String origin) {
        assertTrue(CONFIGURED.permits(origin));
        assertFalse(DEFAULT.permits(origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example.com", "null"})
    void starDisablesTheCheck(String origin) {
        assertTrue(ANY.permits(origin));
    }

    @Test
    void absentOriginPasses() {
        assertTrue(DEFAULT.permits(null));
    }
}
