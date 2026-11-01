package io.intimed;

import io.intimed.cli.Sdk;
import io.intimed.security.pki.LocalPki;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * IntiMed platform entry point. {@code java -jar intimed-platform.jar} starts the platform (console HTTPS 9443, device
 * ingress mTLS 8443, MLLP-TLS 2575); {@code java -jar intimed-platform.jar sdk <command>} runs the driver SDK / device
 * simulator / gateway CLI without starting the server.
 */
@SpringBootApplication
public class IntiMedApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "sdk".equals(args[0])) {
            System.exit(Sdk.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        prepareTls();
        SpringApplication.run(IntiMedApplication.class, args);
    }

    /** Bootstraps the local PKI and points the HTTPS connector at the generated keystore (SC-01, mkcert-equivalent). */
    public static void prepareTls() {
        LocalPki pki = LocalPki.get();
        Path ks = pki.serverKeystore();
        System.setProperty("server.ssl.key-store", ks.toString());
        System.setProperty("server.ssl.key-store-password", new String(pki.keystorePassword()));
        System.setProperty("server.ssl.key-store-type", "PKCS12");
        System.setProperty("server.ssl.key-alias", "intimed");
        LocalPki.writePem(pki.dir().resolve("root-ca.pem"), pki.root());
    }
}
