package io.github.jungm.crema.internal.security;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Helpers for log messages about requests, which unauthenticated clients can trigger at will.
 */
final class LogText {

    private LogText() {
    }

    /**
     * Text that a client or an Authorization Server influences, such as an exception message that quotes a token's
     * claims, with control characters and line and paragraph separators removed, so it can't forge log lines.
     *
     * @return the text, or {@code "null"} for {@code null}
     */
    static String clean(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder clean = new StringBuilder(text.length());
        text.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.LINE_SEPARATOR
                && Character.getType(c) != Character.PARAGRAPH_SEPARATOR).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    /**
     * Lets at most one message through per interval.
     */
    static final class Throttle {

        private final long intervalNanos;
        private final LongSupplier nanoTime;
        private final AtomicLong next;

        Throttle(long interval, TimeUnit unit) {
            this(interval, unit, System::nanoTime);
        }

        Throttle(long interval, TimeUnit unit, LongSupplier nanoTime) {
            this.intervalNanos = unit.toNanos(interval);
            this.nanoTime = nanoTime;
            this.next = new AtomicLong(nanoTime.getAsLong());
        }

        /**
         * Whether a message may be logged now; if so, the next one may be logged one interval later.
         */
        boolean permit() {
            long now = nanoTime.getAsLong();
            long due = next.get();
            return now - due >= 0 && next.compareAndSet(due, now + intervalNanos);
        }
    }
}
