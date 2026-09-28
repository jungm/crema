package io.github.jungm.crema.it.protocol.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.ContentEncoder;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.TextContent;

/** Content encoders for the measurement types; the most specific one wins. */
public final class Encoders {

    private Encoders() {
    }

    @ApplicationScoped
    public static class MeasurementEncoder implements ContentEncoder<Model.Measurement> {
        @Override
        public ContentBlock encode(Model.Measurement value) {
            return TextContent.of("measurement " + value.getValue());
        }

        @Override
        public Class<Model.Measurement> getType() {
            return Model.Measurement.class;
        }
    }

    @ApplicationScoped
    public static class TemperatureEncoder implements ContentEncoder<Model.Temperature> {
        @Override
        public ContentBlock encode(Model.Temperature value) {
            return TextContent.of(value.getValue() + " degrees Celsius");
        }

        @Override
        public Class<Model.Temperature> getType() {
            return Model.Temperature.class;
        }
    }
}
