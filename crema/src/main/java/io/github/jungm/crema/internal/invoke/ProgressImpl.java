package io.github.jungm.crema.internal.invoke;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.progress.ProgressNotification;
import org.mcpjava.server.progress.ProgressToken;
import org.mcpjava.server.progress.ProgressTracker;

import io.github.jungm.crema.internal.json.ProtocolJson;
import io.github.jungm.crema.internal.protocol.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * The {@link Progress} of one request. Notifications go to the request's {@link ProgressChannel}. As the API
 * specifies, {@link #notificationBuilder()} and {@link #trackerBuilder()} throw an {@link IllegalStateException}
 * when the request has no progress token. {@code progress} must increase with each notification: a notification
 * that doesn't increase it fails with an {@link IllegalArgumentException} and isn't sent. {@code send()} returns a
 * {@code CompletableFuture<Void>}.
 */
public final class ProgressImpl implements Progress {

    private final ProgressTokenImpl token;
    private final ProgressChannel channel;
    private final Function<Object, JsonValue> encoder;
    private BigDecimal last;

    /**
     * @param token the request's progress token, or {@code null}
     * @param encoder encodes {@code _meta} values
     */
    public ProgressImpl(ProgressTokenImpl token, ProgressChannel channel, Function<Object, JsonValue> encoder) {
        this.token = token;
        this.channel = channel;
        this.encoder = encoder;
    }

    @Override
    public Optional<ProgressToken> token() {
        return Optional.ofNullable(token);
    }

    @Override
    public ProgressNotification.Builder notificationBuilder() {
        requireToken();
        return new NotificationBuilder();
    }

    @Override
    public ProgressTracker.Builder trackerBuilder() {
        requireToken();
        return new TrackerBuilder();
    }

    synchronized CompletableFuture<Void> send(BigDecimal progress, BigDecimal total, String message,
            Map<String, Object> metadata) {
        if (last != null && progress.compareTo(last) <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Progress must increase with each notification, but " + progress + " follows " + last));
        }
        last = progress;
        JsonObjectBuilder params = Json.object().add("progressToken", token.json()).add("progress", progress);
        if (total != null) {
            params.add("total", total);
        }
        if (message != null) {
            params.add("message", message);
        }
        if (!metadata.isEmpty()) {
            params.add("_meta", ProtocolJson.meta(metadata, encoder));
        }
        return channel.send(Json.object().add("jsonrpc", "2.0").add("method", "notifications/progress")
                .add("params", params).build());
    }

    private ProgressToken requireToken() {
        if (token == null) {
            throw new IllegalStateException("The request has no progress token");
        }
        return token;
    }

    private final class NotificationBuilder implements ProgressNotification.Builder {

        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private BigDecimal progress = BigDecimal.ZERO;
        private BigDecimal total;
        private String message;

        @Override
        public ProgressNotification.Builder putMetadata(String key, Object value) {
            metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        @Override
        public ProgressNotification.Builder setMetadata(Map<String, Object> metadata) {
            this.metadata.clear();
            this.metadata.putAll(metadata);
            return this;
        }

        @Override
        public ProgressNotification.Builder setProgress(long progress) {
            this.progress = BigDecimal.valueOf(progress);
            return this;
        }

        @Override
        public ProgressNotification.Builder setProgress(double progress) {
            this.progress = BigDecimal.valueOf(progress);
            return this;
        }

        @Override
        public ProgressNotification.Builder setTotal(long total) {
            this.total = BigDecimal.valueOf(total);
            return this;
        }

        @Override
        public ProgressNotification.Builder setTotal(double total) {
            this.total = BigDecimal.valueOf(total);
            return this;
        }

        @Override
        public ProgressNotification.Builder setMessage(String message) {
            this.message = message;
            return this;
        }

        @Override
        public ProgressNotification build() {
            return new Notification(progress, total, message,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        }
    }

    private final class Notification implements ProgressNotification {

        private final BigDecimal progress;
        private final BigDecimal total;
        private final String message;
        private final Map<String, Object> metadata;

        Notification(BigDecimal progress, BigDecimal total, String message, Map<String, Object> metadata) {
            this.progress = progress;
            this.total = total;
            this.message = message;
            this.metadata = metadata;
        }

        @Override
        public ProgressToken token() {
            return token;
        }

        @Override
        public Optional<BigDecimal> total() {
            return Optional.ofNullable(total);
        }

        @Override
        public BigDecimal progress() {
            return progress;
        }

        @Override
        public Optional<String> message() {
            return Optional.ofNullable(message);
        }

        @Override
        public Map<String, Object> metadata() {
            return metadata;
        }

        @Override
        public void sendAndForget() {
            send();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T send() {
            return (T) ProgressImpl.this.send(progress, total, message, metadata);
        }
    }

    private final class TrackerBuilder implements ProgressTracker.Builder {

        private BigDecimal total;
        private BigDecimal step = BigDecimal.ONE;
        private Function<BigDecimal, String> messageBuilder = progress -> null;

        @Override
        public ProgressTracker.Builder setTotal(long total) {
            this.total = BigDecimal.valueOf(total);
            return this;
        }

        @Override
        public ProgressTracker.Builder setTotal(double total) {
            this.total = BigDecimal.valueOf(total);
            return this;
        }

        @Override
        public ProgressTracker.Builder setDefaultStep(long step) {
            this.step = BigDecimal.valueOf(step);
            return this;
        }

        @Override
        public ProgressTracker.Builder setDefaultStep(double step) {
            this.step = BigDecimal.valueOf(step);
            return this;
        }

        @Override
        public ProgressTracker.Builder setMessageBuilder(Function<BigDecimal, String> messageBuilder) {
            this.messageBuilder = Objects.requireNonNull(messageBuilder, "messageBuilder");
            return this;
        }

        @Override
        public ProgressTracker build() {
            return new Tracker(total, step, messageBuilder);
        }
    }

    private final class Tracker implements ProgressTracker {

        private final BigDecimal total;
        private final BigDecimal step;
        private final Function<BigDecimal, String> messageBuilder;
        private BigDecimal progress = BigDecimal.ZERO;

        Tracker(BigDecimal total, BigDecimal step, Function<BigDecimal, String> messageBuilder) {
            this.total = total;
            this.step = step;
            this.messageBuilder = messageBuilder;
        }

        @Override
        public ProgressToken token() {
            return token;
        }

        @Override
        public void advanceAndForget(BigDecimal value) {
            advance(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> T advance(BigDecimal value) {
            BigDecimal next = progress.add(value);
            if (total != null && next.compareTo(total) > 0) {
                throw new IllegalStateException("Progress " + next + " would exceed the total " + total);
            }
            progress = next;
            return (T) send(next, total, messageBuilder.apply(next), Map.of());
        }

        @Override
        public synchronized BigDecimal progress() {
            return progress;
        }

        @Override
        public Optional<BigDecimal> total() {
            return Optional.ofNullable(total);
        }

        @Override
        public BigDecimal step() {
            return step;
        }
    }
}
