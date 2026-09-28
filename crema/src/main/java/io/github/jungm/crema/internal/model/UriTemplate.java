package io.github.jungm.crema.internal.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An RFC 6570 Level 1 URI template such as {@code db:///{database}/tables/{table}}. A variable matches a
 * non-empty value without {@code /}; percent-encoded octets in the value are decoded as UTF-8.
 */
public final class UriTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern VARNAME = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");

    private final String template;
    private final List<String> variables;
    private final Pattern pattern;

    private UriTemplate(String template, List<String> variables, Pattern pattern) {
        this.template = template;
        this.variables = variables;
        this.pattern = pattern;
    }

    /**
     * Parses a Level 1 template.
     *
     * @throws IllegalArgumentException if the template has unbalanced braces, operators, modifiers or repeated
     *         variables
     */
    public static UriTemplate parse(String template) {
        List<String> variables = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        Matcher matcher = VARIABLE.matcher(template);
        int end = 0;
        while (matcher.find()) {
            regex.append(literal(template, template.substring(end, matcher.start())));
            String name = matcher.group(1);
            if (!VARNAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'{" + name + "}' in URI template '" + template
                        + "' isn't a Level 1 variable; use {name} with letters, digits and '_'");
            }
            if (variables.contains(name)) {
                throw new IllegalArgumentException(
                        "Variable '" + name + "' appears more than once in URI template '" + template + "'");
            }
            variables.add(name);
            regex.append("([^/]+)");
            end = matcher.end();
        }
        regex.append(literal(template, template.substring(end)));
        return new UriTemplate(template, List.copyOf(variables), Pattern.compile(regex.toString()));
    }

    public String template() {
        return template;
    }

    public List<String> variables() {
        return variables;
    }

    /**
     * The variable values if {@code uri} matches this template.
     */
    public Optional<Map<String, String>> match(String uri) {
        Matcher matcher = pattern.matcher(uri);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < variables.size(); i++) {
            values.put(variables.get(i), decode(matcher.group(i + 1)));
        }
        return Optional.of(values);
    }

    private static String literal(String template, String literal) {
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) {
            throw new IllegalArgumentException("Unbalanced braces in URI template '" + template + "'");
        }
        return literal.isEmpty() ? "" : Pattern.quote(literal);
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write(high << 4 | low);
                    i += 3;
                    continue;
                }
            }
            int codePoint = value.codePointAt(i);
            byte[] raw = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            bytes.write(raw, 0, raw.length);
            i += Character.charCount(codePoint);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return template;
    }
}
