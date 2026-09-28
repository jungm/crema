package io.github.jungm.crema.internal.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.mcpjava.server.Icon;

/**
 * Immutable {@link Icon}. Sizes are strings in {@code "WxH"} format or {@code "any"}; the public
 * constructor takes them verbatim, for icons parsed from the wire.
 */
public record IconImpl(String src, Optional<String> mimeType, List<String> sizes, Optional<Theme> theme)
        implements Icon {

    private static final String ANY = "any";

    public IconImpl {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(mimeType, "mimeType");
        sizes = List.copyOf(sizes);
        Objects.requireNonNull(theme, "theme");
    }

    static final class Builder implements Icon.Builder {

        private final String src;
        private final List<String> sizes = new ArrayList<>();
        private String mimeType;
        private Theme theme;
        private boolean anySize;

        Builder(String src) {
            this.src = Objects.requireNonNull(src, "uri");
        }

        @Override
        public Icon.Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        @Override
        public Icon.Builder addSize(int width, int height) {
            if (anySize) {
                throw new IllegalStateException("addSize can't be combined with setAnySize");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Icon size must be positive: " + width + "x" + height);
            }
            sizes.add(width + "x" + height);
            return this;
        }

        @Override
        public Icon.Builder setAnySize() {
            if (!sizes.isEmpty() && !anySize) {
                throw new IllegalStateException("setAnySize can't be combined with addSize");
            }
            anySize = true;
            sizes.clear();
            sizes.add(ANY);
            return this;
        }

        @Override
        public Icon.Builder setTheme(Theme theme) {
            this.theme = theme;
            return this;
        }

        @Override
        public Icon build() {
            return new IconImpl(src, Optional.ofNullable(mimeType), sizes, Optional.ofNullable(theme));
        }
    }
}
