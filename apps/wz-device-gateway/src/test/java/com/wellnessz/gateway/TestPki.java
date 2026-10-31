package com.wellnessz.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Throw-away client key stores for tests, created with the JDK's keytool (synthetic identities only). */
public final class TestPki {

    public static final String PASSWORD = "test-only-pw";

    private TestPki() {
    }

    public static Path clientKeyStore(Path dir, String cn) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path ks = dir.resolve(cn + ".p12");
        if (Files.exists(ks)) {
            return ks;
        }
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "gw", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=" + cn + ",O=WellnessZ Test", "-validity", "365",
                "-ext", "EKU=clientAuth", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD))
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        return ks;
    }
}
