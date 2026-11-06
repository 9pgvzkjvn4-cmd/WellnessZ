package io.intimed;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One temporary platform home per test JVM. The Spring Boot integration test classes share the same
 * {@code @SpringBootTest} configuration (and therefore one cached application context), so the home directory, the
 * encrypted H2 file and the local PKI must be prepared exactly once, whichever class runs first.
 */
final class TestHome {
    private static boolean done;

    private TestHome() { }

    static synchronized void init() {
        if (done) return;
        try {
            Path home = Files.createTempDirectory("intimed-it");
            System.setProperty("intimed.home", home.toString());
            System.setProperty("spring.datasource.url", "jdbc:h2:file:" + home + "/db/intimed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CIPHER=AES");
            IntiMedApplication.prepareTls();
            done = true;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
