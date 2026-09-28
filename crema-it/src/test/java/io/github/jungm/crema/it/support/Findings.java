package io.github.jungm.crema.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Observations about a Runtime's behaviour, printed and collected in {@code target/findings/<runtime>-<test>.txt}
 * for the findings table in {@code crema-it/README.md}.
 */
public final class Findings {

    public static final String RUNTIME = System.getProperty("crema.it.runtime", "unknown");

    private final Path file;

    private Findings(Path file) {
        this.file = file;
    }

    /** Starts a fresh findings file for one test class. */
    public static Findings of(Class<?> testClass) {
        String dir = System.getProperty("crema.it.findings.dir");
        Path file = dir == null ? null : Path.of(dir, RUNTIME + "-" + testClass.getSimpleName() + ".txt");
        try {
            if (file != null) {
                Files.createDirectories(file.getParent());
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Findings(file);
    }

    public void record(String item, String outcome) {
        String line = RUNTIME + " | " + item + " | " + outcome;
        System.out.println("FINDING " + line);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
