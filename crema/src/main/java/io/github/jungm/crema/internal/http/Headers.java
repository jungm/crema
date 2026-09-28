package io.github.jungm.crema.internal.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The header fields of a request, by case-insensitive name.
 */
public final class Headers {

    /**
     * A request without header fields.
     */
    public static final Headers NONE = new Headers(Map.of());

    private final Map<String, ? extends List<String>> fields;

    private Headers(Map<String, ? extends List<String>> fields) {
        this.fields = fields;
    }

    /**
     * The header fields of a map whose keys may differ in case from the names asked for; the map is read, not
     * copied.
     */
    public static Headers of(Map<String, ? extends List<String>> fields) {
        return new Headers(fields);
    }

    /**
     * The values of a header field, in order; empty when it is absent.
     */
    public List<String> all(String name) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, ? extends List<String>> entry : fields.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                values.addAll(entry.getValue());
            }
        }
        return values;
    }

    /**
     * The first value of a header field, or {@code null} when it is absent.
     */
    public String first(String name) {
        List<String> values = all(name);
        return values.isEmpty() ? null : values.get(0);
    }
}
