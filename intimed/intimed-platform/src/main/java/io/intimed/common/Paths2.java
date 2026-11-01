package io.intimed.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Home directory of the local installation (keys, PKI, database, evidence). Default ./intimed-data. */
public final class Paths2 {
    private Paths2() { }

    public static Path home() {
        String h = System.getProperty("intimed.home", System.getenv().getOrDefault("INTIMED_HOME", "intimed-data"));
        Path p = Path.of(h).toAbsolutePath();
        try { Files.createDirectories(p); } catch (IOException e) { throw new UncheckedIOException(e); }
        return p;
    }

    public static Path dir(String sub) {
        Path p = home().resolve(sub);
        try { Files.createDirectories(p); restrict(p, true); } catch (IOException e) { throw new UncheckedIOException(e); }
        return p;
    }

    /** Owner-only permissions on key material (no-op on non-POSIX file systems). */
    public static void restrict(Path p, boolean dir) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: rely on the user profile ACL
        }
    }

    public static void writeSecret(Path p, byte[] content) {
        try {
            Files.write(p, content);
            restrict(p, false);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
