package io.github.jungm.crema.it.protocol.app;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;

/** Counts calls across requests. */
@ApplicationScoped
public class CallLog {

    private final AtomicInteger calls = new AtomicInteger();

    public int next() {
        return calls.incrementAndGet();
    }
}
