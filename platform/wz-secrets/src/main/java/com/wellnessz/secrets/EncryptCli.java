package com.wellnessz.secrets;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Command-line helper to produce {@code ENC(...)} values for configuration (e.g. database passwords).
 * The plaintext is read from the console without echo (or stdin when piped) – never from arguments,
 * so it does not end up in shell history or process listings.
 *
 * <pre>
 * export WZ_MASTER_KEY=$(openssl rand -base64 32)   # or WZ_MASTER_KEY_FILE
 * java -cp wz-secrets.jar com.wellnessz.secrets.EncryptCli
 * </pre>
 */
public final class EncryptCli {

    private EncryptCli() {
    }

    public static void main(String[] args) throws IOException {
        byte[] key = MasterKey.load(System::getenv);
        if (key == null) {
            System.err.println("Set " + MasterKey.ENV_KEY + " or " + MasterKey.ENV_KEY_FILE + " first.");
            System.exit(2);
            return;
        }
        String plaintext;
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("Secret to encrypt: ");
            plaintext = chars == null ? "" : new String(chars);
        } else {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            plaintext = line == null ? "" : line;
        }
        if (plaintext.isEmpty()) {
            System.err.println("Nothing to encrypt.");
            System.exit(1);
            return;
        }
        System.out.println(new SecretValueResolver(key).encrypt(plaintext));
    }
}
