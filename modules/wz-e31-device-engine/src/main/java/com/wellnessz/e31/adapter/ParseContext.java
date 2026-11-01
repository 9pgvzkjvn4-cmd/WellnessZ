package com.wellnessz.e31.adapter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import com.wellnessz.e31.driver.DriverPackage;

/**
 * Input of the PARSE stage.
 *
 * @param zone time zone for device times without an offset (driver {@code timeZone}, else the instance time zone, else UTC)
 */
public record ParseContext(DriverPackage pkg, byte[] body, String contentType, Map<String, String> headers, Instant receivedAt, ZoneId zone) {

    public ParseContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Charset from the content type ({@code charset=}), the driver protocol {@code encoding}, else the given default. */
    public Charset charset(Charset fallback) {
        String ct = contentType == null ? "" : contentType;
        int i = ct.toLowerCase(java.util.Locale.ROOT).indexOf("charset=");
        String name = i >= 0 ? ct.substring(i + 8).split(";")[0].strip().replace("\"", "") : pkg.protocolText("encoding");
        if (name != null && !name.isBlank()) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public String text() {
        return new String(body, charset(StandardCharsets.UTF_8));
    }

    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
