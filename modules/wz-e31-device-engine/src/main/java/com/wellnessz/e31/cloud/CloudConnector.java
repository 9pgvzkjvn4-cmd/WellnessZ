package com.wellnessz.e31.cloud;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.e31.driver.DriverPackage;

import tools.jackson.databind.JsonNode;

/**
 * Effective settings of one P6 vendor-cloud poll connector (E31-BL-008, contract §13.3 P6): the published E32 option parameters
 * ({@code apiBaseUrl}, {@code oauth2{tokenUrl, clientId, clientSecretRef, scopes}}, {@code poll{enabled, intervalSeconds,
 * rateLimitPerMinute, path}}, {@code consentMapping{purpose}}, {@code residencyRegion}) take precedence over the driver package's
 * {@code protocol.poll} defaults; {@code PARAMETER:x} references of the package resolve against the parameters (flat key, then
 * the {@code oauth2} / {@code poll} / {@code webhook} groups). The response layout ({@code recordsPointer}, {@code cursorParam},
 * {@code nextCursorPointer}, {@code recordIdPointer}, {@code recordTimePointer}) belongs to the driver package.
 */
public record CloudConnector(String deviceInstanceId, String tenantCode, String siteCode, boolean enabled, String apiBaseUrl, String path,
                             String tokenUrl, String clientId, String clientSecretRef, List<String> scopes, int intervalSeconds,
                             int rateLimitPerMinute, String recordsPointer, String cursorParam, String nextCursorPointer, String recordIdPointer,
                             String recordTimePointer, String subjectPointer, String consentPurpose, String residencyRegion, int pageSize,
                             int maxPagesPerRun) {

    public static final int MIN_INTERVAL_SECONDS = 60;

    /** Builds the effective settings; {@code null} when the driver declares no polling at all. */
    public static CloudConnector of(DeviceCatalogueLookup.DeviceInstance i, DriverPackage pkg) {
        JsonNode poll = pkg.protocol() == null ? null : pkg.protocol().get("poll");
        if (poll == null || poll.isNull()) {
            return null;
        }
        Map<String, Object> p = i.parameters();
        Map<String, Object> pollParams = map(p.get("poll"));
        Map<String, Object> oauth = map(p.get("oauth2"));
        JsonNode driverOauth = poll.path("oauth2");
        boolean enabled = bool(pollParams.get("enabled"), poll.path("enabled").asBoolean(false));
        String base = str(p.get("apiBaseUrl"), resolve(poll.path("url").asString(null), p));
        String tokenUrl = str(oauth.get("tokenUrl"), resolve(driverOauth.path("tokenUrl").asString(null), p));
        String clientId = str(oauth.get("clientId"), resolve(driverOauth.path("clientId").asString(null), p));
        String secretRef = str(oauth.get("clientSecretRef"), resolve(driverOauth.path("secretRef").asString(null), p));
        List<String> scopes = new ArrayList<>();
        Object paramScopes = oauth.get("scopes");
        if (paramScopes instanceof List<?> l && !l.isEmpty()) {
            l.forEach(s -> scopes.add(String.valueOf(s)));
        } else {
            driverOauth.path("scopes").forEach(s -> scopes.add(s.asString()));
        }
        int interval = Math.max(MIN_INTERVAL_SECONDS, integer(pollParams.get("intervalSeconds"), poll.path("intervalSeconds").asInt(300)));
        int rate = Math.max(1, integer(pollParams.get("rateLimitPerMinute"), poll.path("rateLimitPerMinute").asInt(60)));
        String path = str(pollParams.get("path"), poll.path("path").asString("/"));
        Map<String, Object> consent = map(p.get("consentMapping"));
        String purpose = str(consent.get("purpose"), pkg.protocol().path("consentPurpose").asString(null));
        String subject = pkg.parse() == null || pkg.parse().identity() == null ? null : pkg.parse().identity().employee();
        return new CloudConnector(i.deviceInstanceId(), i.tenantCode(), i.siteCode(), enabled, base, path, tokenUrl, clientId, secretRef,
                List.copyOf(scopes), interval, rate, poll.path("recordsPointer").asString("/items"), poll.path("cursorParam").asString("since"),
                poll.path("nextCursorPointer").asString("/nextCursor"), poll.path("recordIdPointer").asString("/id"),
                poll.path("recordTimePointer").asString(null), subject, purpose, str(p.get("residencyRegion"), null),
                Math.min(500, Math.max(1, poll.path("pageSize").asInt(100))), Math.min(50, Math.max(1, poll.path("maxPagesPerRun").asInt(10))));
    }

    /** The records URL for a page: base + path, with the cursor and page size as query parameters. */
    public URI pageUri(String cursor) {
        StringBuilder u = new StringBuilder(stripSlash(apiBaseUrl));
        u.append(path == null || path.isBlank() ? "" : path.startsWith("/") ? path : "/" + path);
        u.append(u.indexOf("?") < 0 ? '?' : '&').append("limit=").append(pageSize);
        if (cursor != null && !cursor.isBlank()) {
            u.append('&').append(cursorParam).append('=').append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
        }
        return URI.create(u.toString());
    }

    static String host(String url) {
        try {
            String h = url == null ? null : URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code PARAMETER:x} → the parameter value (flat key, then oauth2 / poll / webhook groups); literals unchanged. */
    static String resolve(String value, Map<String, Object> params) {
        if (value == null || !value.startsWith("PARAMETER:")) {
            return value;
        }
        return parameter(params, value.substring("PARAMETER:".length()));
    }

    /** Looks a parameter up by key in the flat map and in the P6 groups (E32 schema nests oauth2 / webhook / poll members). */
    public static String parameter(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            for (String group : List.of("oauth2", "webhook", "poll")) {
                v = map(params.get(group)).get(key);
                if (v != null) {
                    break;
                }
            }
        }
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String str(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    static boolean bool(Object o, boolean fallback) {
        return o == null ? fallback : Boolean.parseBoolean(String.valueOf(o));
    }

    static int integer(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String stripSlash(String s) {
        String out = s == null ? "" : s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
