package io.github.jungm.crema.it.protocol.app;

import java.math.BigDecimal;
import java.util.List;

import jakarta.json.bind.annotation.JsonbProperty;

/** Application value types used as Arguments and return values. */
public final class Model {

    private Model() {
    }

    public enum Priority {
        LOW, NORMAL, HIGH
    }

    public record Item(String sku, int quantity, BigDecimal price) {
    }

    public record Order(String customer, List<Item> items) {
    }

    public record Receipt(@JsonbProperty("order_id") String orderId, BigDecimal total, Priority priority,
            int lines) {
    }

    public record Config(String region, boolean beta, List<String> features) {
    }

    /** A JavaBean with getters and setters, rather than a record. */
    public static class Measurement {
        private double value;

        public Measurement() {
        }

        public Measurement(double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }
    }

    public static class Temperature extends Measurement {
        public Temperature(double value) {
            super(value);
        }
    }

    public static class Humidity extends Measurement {
        public Humidity(double value) {
            super(value);
        }
    }

    public record RequestView(String id, String protocolVersion, String clientName, String clientVersion,
            boolean sessionId, List<String> metadataKeys, List<String> capabilities) {
    }
}
