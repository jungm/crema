package io.github.jungm.crema.internal.bind;

/**
 * A JSON value can't be bound to a Java type. The message is short and addressed to a language model, for example
 * {@code items[2]: expected an integer but got "abc"}.
 */
public class BindingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String path;
    private final String detail;

    public BindingException(String detail) {
        this("", detail, null);
    }

    public BindingException(String detail, Throwable cause) {
        this("", detail, cause);
    }

    private BindingException(String path, String detail, Throwable cause) {
        super(path.isEmpty() ? detail : path + ": " + detail, cause);
        this.path = path;
        this.detail = detail;
    }

    /**
     * Returns the location of the offending value relative to the bound value, such as {@code [2].name}, or the
     * empty string if the bound value itself is the offending value.
     */
    public String path() {
        return path;
    }

    /**
     * Returns the message without the path.
     */
    public String detail() {
        return detail;
    }

    /**
     * Returns a copy of this exception located inside the array element with the given index.
     */
    public BindingException atIndex(int index) {
        return prefixed("[" + index + "]");
    }

    /**
     * Returns a copy of this exception located inside the object member with the given name.
     */
    public BindingException atMember(String name) {
        return prefixed(name);
    }

    private BindingException prefixed(String prefix) {
        String separator = path.isEmpty() || path.startsWith("[") ? "" : ".";
        BindingException copy = new BindingException(prefix + separator + path, detail, getCause());
        copy.setStackTrace(getStackTrace());
        return copy;
    }
}
