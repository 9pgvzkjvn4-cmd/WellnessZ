package com.wellnessz.gateway.core;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Response of the core ingest API. */
public record CoreResponse(int status, Map<String, List<String>> headers, byte[] body) {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"");

    public CoreResponse {
        headers = headers == null ? Map.of() : headers;
        body = body == null ? new byte[0] : body;
    }

    public boolean success() {
        return status >= 200 && status < 300;
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream()).findFirst();
    }

    /** The RFC 9457 problem {@code code} (e.g. {@code endpoint_isolated}), when present. */
    public Optional<String> problemCode() {
        Matcher m = CODE.matcher(text());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Retryable: rate limited, server-side failure or gateway timeout. */
    public boolean retryable() {
        return status == 408 || status == 429 || status >= 500;
    }
}
