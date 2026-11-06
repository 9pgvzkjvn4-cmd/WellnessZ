package io.intimed.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Secret values from the environment or from mounted files – the latter is how the GKE Secret Manager add-on and other
 * CSI drivers deliver secrets (files, no Kubernetes Secret). Resolution order for {@code NAME}: environment variable
 * {@code NAME}, file named by {@code NAME_FILE}, file {@code $INTIMED_SECRETS_DIR/NAME} (default /mnt/secrets).
 */
public final class Secrets {
    private Secrets() { }

    public static String get(String name) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v.trim();
        String f = System.getenv(name + "_FILE");
        if (f != null && !f.isBlank()) return read(Path.of(f));
        Path p = Path.of(System.getenv().getOrDefault("INTIMED_SECRETS_DIR", "/mnt/secrets")).resolve(name);
        return Files.isRegularFile(p) ? read(p) : null;
    }

    private static String read(Path p) {
        try { return Files.readString(p, StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { throw new IllegalStateException("secret file unreadable: " + p.getFileName()); }
    }
}
