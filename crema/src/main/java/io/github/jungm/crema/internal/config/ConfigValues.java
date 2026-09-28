package io.github.jungm.crema.internal.config;

/**
 * Parsing of typed configuration values, with messages that name the key.
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    /**
     * Parses an integer that must be at least {@code min}, ignoring surrounding whitespace.
     *
     * @param key the configuration key, for the message
     * @param unit what the number counts, such as {@code milliseconds}, for the message
     * @throws IllegalArgumentException if the value isn't an integer, or is below {@code min}, e.g.
     *         {@code crema.cache.list-ttl-ms must be an integer number of milliseconds >= 0, but is 'soon'}
     */
    public static long integer(String key, String value, long min, String unit) {
        long number;
        try {
            number = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(key, value, min, unit);
        }
        if (number < min) {
            throw invalid(key, value, min, unit);
        }
        return number;
    }

    private static IllegalArgumentException invalid(String key, String value, long min, String unit) {
        return new IllegalArgumentException(
                key + " must be an integer number of " + unit + " >= " + min + ", but is '" + value + "'");
    }
}
