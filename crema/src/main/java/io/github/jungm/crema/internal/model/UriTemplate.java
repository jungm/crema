package io.github.jungm.crema.internal.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An RFC 6570 Level 1 URI template such as {@code db:///{database}/tables/{table}}. A variable matches a
 * non-empty value without {@code /}; percent-encoded octets in the value are decoded as UTF-8. A URI doesn't match
 * if a decoded value is empty, contains {@code /} or {@code \}, starts with a Windows drive such as {@code C:}, or
 * is {@code .} or {@code ..}, so each value is safe to use as a single path segment.
 */
public final class UriTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern VARNAME = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");

    private final String template;
    private final List<String> variables;
    private final List<String> literals;
    private final String shape;

    private UriTemplate(String template, List<String> variables, List<String> literals, String shape) {
        this.template = template;
        this.variables = variables;
        this.literals = literals;
        this.shape = shape;
    }

    /**
     * Parses a Level 1 template.
     *
     * @throws IllegalArgumentException if the template has unbalanced braces, operators, modifiers or repeated
     *         variables
     */
    public static UriTemplate parse(String template) {
        List<String> variables = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        StringBuilder shape = new StringBuilder();
        Matcher matcher = VARIABLE.matcher(template);
        int end = 0;
        while (matcher.find()) {
            String literal = literal(template, template.substring(end, matcher.start()));
            literals.add(literal);
            shape.append(literal).append("{}");
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
            end = matcher.end();
        }
        String last = literal(template, template.substring(end));
        literals.add(last);
        shape.append(last);
        return new UriTemplate(template, List.copyOf(variables), List.copyOf(literals), shape.toString());
    }

    public String template() {
        return template;
    }

    public List<String> variables() {
        return variables;
    }

    /**
     * The template without its variable names, such as {@code db:///{}/tables/{}}. Templates with the same shape
     * match the same URIs.
     */
    public String shape() {
        return shape;
    }

    /**
     * The variable values if {@code uri} matches this template.
     */
    public Optional<Map<String, String>> match(String uri) {
        int[] ends = valueEnds(uri);
        if (ends == null) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        int start = literals.get(0).length();
        for (int i = 0; i < variables.size(); i++) {
            String value = decode(uri.substring(start, ends[i]));
            if (!isSafe(value)) {
                return Optional.empty();
            }
            values.put(variables.get(i), value);
            start = ends[i] + literals.get(i + 1).length();
        }
        return Optional.of(values);
    }

    /**
     * Where each variable's value ends in {@code uri}, or {@code null} if {@code uri} doesn't match. From left to
     * right, each value is the longest that lets the rest of {@code uri} match, as a backtracking regex with
     * {@code ([^/]+)} per variable would pick it, but in time linear in the length of {@code uri}. Literals and
     * values match whole code points.
     */
    private int[] valueEnds(String uri) {
        int n = uri.length();
        int count = variables.size();
        int lastStart = n - literals.get(count).length();
        if (lastStart < 0 || !uri.startsWith(literals.get(count), lastStart) || splitsSurrogatePair(uri, lastStart)
                || !uri.startsWith(literals.get(0))) {
            return null;
        }
        // rest[i] holds the positions from which the rest of uri matches literal i and everything after it
        BitSet[] rest = new BitSet[count + 1];
        rest[count] = new BitSet();
        rest[count].set(lastStart);
        for (int i = count - 1; i >= 0; i--) {
            String literal = literals.get(i);
            rest[i] = new BitSet();
            // the nearest end after q of a value starting at q, or -1 if there's none
            int nearestEnd = -1;
            for (int q = n - 1; q >= 0; q--) {
                if (uri.charAt(q) == '/') {
                    nearestEnd = -1;
                    continue;
                }
                if (rest[i + 1].get(q + 1)) {
                    nearestEnd = q + 1;
                }
                int p = q - literal.length();
                if (nearestEnd >= 0 && p >= 0 && uri.startsWith(literal, p) && !splitsSurrogatePair(uri, p)
                        && !splitsSurrogatePair(uri, q)) {
                    rest[i].set(p);
                }
            }
            if (rest[i].isEmpty()) {
                return null;
            }
        }
        if (!rest[0].get(0)) {
            return null;
        }
        int[] ends = new int[count];
        int start = literals.get(0).length();
        for (int i = 0; i < count; i++) {
            int slash = uri.indexOf('/', start);
            ends[i] = rest[i + 1].previousSetBit(slash < 0 ? n : slash);
            start = ends[i] + literals.get(i + 1).length();
        }
        return ends;
    }

    private static boolean splitsSurrogatePair(String uri, int index) {
        return index > 0 && index < uri.length() && Character.isHighSurrogate(uri.charAt(index - 1))
                && Character.isLowSurrogate(uri.charAt(index));
    }

    private static boolean isSafe(String value) {
        return !value.isEmpty() && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && !value.equals(".")
                && !value.equals("..") && !startsWithDrive(value);
    }

    /**
     * Whether {@code value} starts with a Windows drive such as {@code C:}, which can make {@code Path.resolve} on
     * Windows leave the base directory.
     */
    private static boolean startsWithDrive(String value) {
        if (value.length() < 2 || value.charAt(1) != ':') {
            return false;
        }
        char drive = value.charAt(0);
        return drive >= 'A' && drive <= 'Z' || drive >= 'a' && drive <= 'z';
    }

    private static String literal(String template, String literal) {
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) {
            throw new IllegalArgumentException("Unbalanced braces in URI template '" + template + "'");
        }
        return literal;
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
