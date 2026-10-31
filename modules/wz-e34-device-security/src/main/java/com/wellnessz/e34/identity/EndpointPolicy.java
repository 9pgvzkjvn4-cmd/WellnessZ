package com.wellnessz.e34.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;

/**
 * Pure zero-trust policy rules applied at authentication (contract §15.3 / §15.4): which ingest paths ("protocols") an
 * endpoint type may call, which zone it must live in, and source-address matching against CIDR ranges. Unit-tested.
 */
public final class EndpointPolicy {

    /** Ingest resources (first path segment after {@code /api/v1/device-ingest/}) that carry device data. */
    public static final Set<String> DATA_PATHS = Set.of("hl7v2", "fhir", "poct1a", "files", "ble", "serial", "dicomweb", "attachments",
            "fallback", "batch", "kiosk");

    private static final Set<String> CONTROL = Set.of("config", "heartbeat", "commands", "certificate-renewal");

    private static final Map<EndpointKind, Set<String>> ALLOWED = new EnumMap<>(EndpointKind.class);

    static {
        ALLOWED.put(EndpointKind.GATEWAY, union(Set.of("hl7v2", "fhir", "poct1a", "files", "ble", "serial", "dicomweb", "attachments",
                "fallback", "batch", "worklist", "secrets")));
        ALLOWED.put(EndpointKind.DEVICE_HOST_AGENT, union(Set.of("hl7v2", "files", "serial", "attachments", "dicomweb", "batch", "worklist",
                "secrets")));
        ALLOWED.put(EndpointKind.MOBILE_APP, union(Set.of("ble", "fallback", "batch", "attachments", "worklist")));
        ALLOWED.put(EndpointKind.KIOSK, union(Set.of("kiosk", "ble")));
        ALLOWED.put(EndpointKind.DATA_MANAGER, union(Set.of("hl7v2", "poct1a", "fhir", "attachments", "batch")));
        ALLOWED.put(EndpointKind.DEVICE, union(Set.of("fhir", "hl7v2", "attachments")));
        ALLOWED.put(EndpointKind.VENDOR_CLOUD, Set.of()); // webhooks (HMAC) and OAuth2 pulls only – never the ingest API
    }

    private static final Pattern SITE = Pattern.compile("[A-Z0-9]{2,10}");

    private EndpointPolicy() {
    }

    private static Set<String> union(Set<String> data) {
        java.util.HashSet<String> s = new java.util.HashSet<>(data);
        s.addAll(CONTROL);
        return Set.copyOf(s);
    }

    /** {@code /api/v1/device-ingest/hl7v2} → {@code hl7v2}; anything unparseable → empty string. */
    public static String resourceOf(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        int idx = p.indexOf("/device-ingest/");
        if (idx < 0) {
            return "";
        }
        p = p.substring(idx + "/device-ingest/".length());
        int slash = p.indexOf('/');
        String first = slash < 0 ? p : p.substring(0, slash);
        int q = first.indexOf('?');
        return (q < 0 ? first : first.substring(0, q)).toLowerCase(Locale.ROOT);
    }

    public static boolean pathAllowed(EndpointKind kind, String path) {
        Set<String> allowed = ALLOWED.getOrDefault(kind, Set.of());
        return allowed.contains(resourceOf(path));
    }

    public static Set<String> allowedResources(EndpointKind kind) {
        return ALLOWED.getOrDefault(kind, Set.of());
    }

    public static boolean isDataPath(String path) {
        return DATA_PATHS.contains(resourceOf(path));
    }

    /** Gateways and Device Host agents must call from their site's GATEWAY_ZONE address ranges (contract §15.4). */
    public static boolean requiresGatewayZone(EndpointKind kind) {
        return kind == EndpointKind.GATEWAY || kind == EndpointKind.DEVICE_HOST_AGENT;
    }

    /** Devices never reach the core directly: a {@code DEVICE} identity in the device VLAN may not authenticate. */
    public static boolean deviceInDeviceVlan(EndpointKind kind, String zone) {
        return kind == EndpointKind.DEVICE && "DEVICE_VLAN".equals(zone);
    }

    /** The default zone of an endpoint kind (contract §15.4). */
    public static String defaultZone(EndpointKind kind) {
        return switch (kind) {
            case GATEWAY, DEVICE_HOST_AGENT, DATA_MANAGER -> "GATEWAY_ZONE";
            case MOBILE_APP, KIOSK -> "CLINIC_LAN";
            case VENDOR_CLOUD -> "VENDOR_CLOUD";
            case DEVICE -> "DEVICE_VLAN";
        };
    }

    /** Short code used in endpoint ids {@code EP-<SITE>-<CODE>-<NN>}. */
    public static String kindCode(EndpointKind kind) {
        return switch (kind) {
            case GATEWAY -> "GW";
            case DEVICE_HOST_AGENT -> "AGT";
            case MOBILE_APP -> "TAB";
            case KIOSK -> "KSK";
            case DATA_MANAGER -> "DM";
            case VENDOR_CLOUD -> "VC";
            case DEVICE -> "DEV";
        };
    }

    public static boolean validSite(String site) {
        return site != null && SITE.matcher(site).matches();
    }

    /** True when the address lies in any of the CIDR ranges; malformed ranges or addresses never match (fail closed). */
    public static boolean inAny(String address, List<String> cidrs) {
        if (address == null || cidrs == null) {
            return false;
        }
        for (String c : cidrs) {
            if (inCidr(address, c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean inCidr(String address, String cidr) {
        if (address == null || cidr == null) {
            return false;
        }
        String c = cidr.strip();
        int slash = c.indexOf('/');
        String net = slash < 0 ? c : c.substring(0, slash);
        byte[] a = literal(address.strip());
        byte[] n = literal(net);
        if (a == null || n == null) {
            return false;
        }
        a = unmapIpv4(a);
        n = unmapIpv4(n);
        if (a.length != n.length) {
            return false;
        }
        int bits;
        try {
            bits = slash < 0 ? a.length * 8 : Integer.parseInt(c.substring(slash + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (bits < 0 || bits > a.length * 8) {
            return false;
        }
        int full = bits / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != n[i]) {
                return false;
            }
        }
        int rest = bits % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[full] & mask) == (n[full] & mask);
    }

    public static boolean validCidr(String cidr) {
        if (cidr == null) {
            return false;
        }
        String c = cidr.strip();
        int slash = c.indexOf('/');
        if (slash < 0) {
            return false;
        }
        byte[] n = literal(c.substring(0, slash));
        if (n == null) {
            return false;
        }
        try {
            int bits = Integer.parseInt(c.substring(slash + 1));
            return bits >= 0 && bits <= n.length * 8;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Parses only IP literals (never resolves host names). */
    static byte[] literal(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean v4 = s.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean v6 = s.indexOf(':') >= 0 && s.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) {
            return null;
        }
        if (v4) {
            for (String part : s.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] unmapIpv4(byte[] a) {
        if (a.length == 16) {
            for (int i = 0; i < 10; i++) {
                if (a[i] != 0) {
                    return a;
                }
            }
            if ((a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) {
                return new byte[] {a[12], a[13], a[14], a[15]};
            }
        }
        return a;
    }
}
