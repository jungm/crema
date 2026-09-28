package io.github.jungm.crema.internal.security;

import static io.github.jungm.crema.internal.security.Fixture.ENDPOINT;
import static io.github.jungm.crema.internal.security.Fixture.OTHER_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.jungm.crema.internal.security.Fixture.RequestCaller;

/**
 * What rejecting tokens logs: unauthenticated clients can trigger it at will, so it must neither flood the log nor
 * let them forge log lines.
 */
class RejectionLoggingTest {

    private static final Logger LOGGER = Logger.getLogger(CremaAccessPolicy.class.getName());

    private final FakeAuthorizationServer as = new FakeAuthorizationServer();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level level;
    private Fixture fixture;

    @BeforeEach
    void capture() {
        level = LOGGER.getLevel();
        LOGGER.setLevel(Level.FINE);
        handler.setLevel(Level.ALL);
        LOGGER.addHandler(handler);
    }

    @AfterEach
    void close() {
        LOGGER.removeHandler(handler);
        LOGGER.setLevel(level);
        if (fixture != null) {
            fixture.policy.close();
        }
        as.close();
    }

    private void fixture() {
        fixture = new Fixture(Fixture.protection(as, "default", ENDPOINT, "groups", false),
                Fixture.protection(as, "other", OTHER_ENDPOINT, "groups", false));
    }

    private int status(String token) {
        return fixture.callTool(fixture.protectedServer, RequestCaller.bearer(token), "whoami").status();
    }

    private List<LogRecord> atLeast(Level level) {
        return records.stream().filter(r -> r.getLevel().intValue() >= level.intValue()).toList();
    }

    @Test
    void tokensWithUnknownKidsAreRejectedAtFine() {
        fixture();
        RSAKey unknown = FakeAuthorizationServer.rsa("unknown");
        List<String> tokens = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID("random-" + i).build(), FakeAuthorizationServer.rsaSigner(unknown),
                        as.claims(ENDPOINT, c -> {
                        })))
                .toList();
        assertEquals(200, status(as.token(ENDPOINT)));
        for (String token : tokens) {
            assertEquals(401, status(token));
        }
        assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("retrieved too recently")),
                "the rate limit is reached");
        assertEquals(List.of(), atLeast(Level.INFO), "no warnings");
        assertTrue(records.stream().allMatch(r -> r.getThrown() == null), "no stack traces");
        assertTrue(records.size() >= 10, "every rejection is logged at FINE");
    }

    @Test
    void unavailableKeysAreWarnedAboutOncePerMinuteWithoutStackTrace() {
        as.jwksDown(true);
        fixture();
        for (int i = 0; i < 5; i++) {
            assertEquals(401, status(as.token(ENDPOINT)));
        }
        List<LogRecord> warnings = atLeast(Level.WARNING);
        assertEquals(1, warnings.size(), warnings.stream().map(LogRecord::getMessage).toList().toString());
        assertTrue(warnings.get(0).getMessage().contains("the keys of issuer " + as.issuer() + " aren't available"),
                warnings.get(0).getMessage());
        assertNull(warnings.get(0).getThrown());
        assertTrue(records.stream().allMatch(r -> r.getThrown() == null), "no stack traces");
    }

    @Test
    void controlCharactersFromTheTokenAreStripped() {
        fixture();
        assertEquals(401, status(FakeAuthorizationServer.sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(as.rsa.getKeyID()).type(new JOSEObjectType("x\r\nSEVERE: forged line")).build(),
                FakeAuthorizationServer.rsaSigner(as.rsa), as.claims(ENDPOINT, c -> {
                }))));
        LogRecord rejection = records.stream().filter(r -> r.getMessage().contains("forged")).findFirst()
                .orElseThrow();
        assertTrue(rejection.getMessage().contains("xSEVERE: forgedline"), rejection.getMessage());
    }

    @Test
    void cleanRemovesControlCharactersAndLineSeparators() {
        assertEquals("abc déf", LogText.clean("a\nb\r\tc d\u0000\u001b\u007f\u0085  éf"));
        assertEquals("null", LogText.clean(null));
    }

    @Test
    void throttlePermitsOncePerInterval() {
        AtomicLong now = new AtomicLong(1_000);
        LogText.Throttle throttle = new LogText.Throttle(1, TimeUnit.MINUTES, now::get);
        assertTrue(throttle.permit());
        assertFalse(throttle.permit());
        now.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertFalse(throttle.permit());
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(throttle.permit());
        assertFalse(throttle.permit());
    }
}
