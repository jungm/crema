package io.github.jungm.crema.it.protocol.app;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.RequestScoped;

/** One instance per request: counts how often it is used within the request. */
@RequestScoped
public class RequestState {

    private final AtomicInteger uses = new AtomicInteger();

    public int use() {
        return uses.incrementAndGet();
    }
}
