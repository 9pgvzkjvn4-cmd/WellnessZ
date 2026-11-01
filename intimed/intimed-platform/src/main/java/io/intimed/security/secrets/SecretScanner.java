package io.intimed.security.secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret scanner used on configuration exports, driver packages and evidence packs (SC-05, XT-13): private keys,
 * cloud keys, bearer tokens, inline passwords/secrets that are not {@code secret://} references, high-entropy strings.
 */
public final class SecretScanner {
    private static final Map<String, Pattern> RULES = Map.of(
            "private-key", Pattern.compile("-----BEGIN (EC |RSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----"),
            "aws-access-key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "bearer-token", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}"),
            "jwt", Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            "inline-credential", Pattern.compile("(?i)\"(password|passwd|secret|clientSecret|client_secret|apiKey|api_key|token|webhookSecret|passkey)\"\\s*:\\s*\"(?!secret://)([^\"]{4,})\""),
            "azure-conn-string", Pattern.compile("AccountKey=[A-Za-z0-9+/=]{20,}"),
            "gcp-sa-key", Pattern.compile("\"type\"\\s*:\\s*\"service_account\""));

    private SecretScanner() { }

    public record Finding(String rule, int offset, String excerpt) { }

    public static List<Finding> scan(String text) {
        List<Finding> out = new ArrayList<>();
        if (text == null) return out;
        RULES.forEach((rule, p) -> {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String ex = text.substring(m.start(), Math.min(text.length(), m.start() + 18)).replaceAll("[A-Za-z0-9]", "x");
                out.add(new Finding(rule, m.start(), ex + "…"));
            }
        });
        return out;
    }
}
