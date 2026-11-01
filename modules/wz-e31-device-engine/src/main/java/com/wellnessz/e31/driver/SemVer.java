package com.wellnessz.e31.driver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic versions of driver packages and the engine-compatibility range ({@code ">=1.0 <2.0"}). */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    static final Pattern VERSION = Pattern.compile("(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})(?:\\.(0|[1-9]\\d{0,4}))?");
    static final Pattern STRICT = Pattern.compile("(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})");
    static final Pattern CONSTRAINT = Pattern.compile("(>=|<=|>|<|=)?\\s*(\\d+(?:\\.\\d+){0,2})");

    public static boolean isStrict(String v) {
        return v != null && STRICT.matcher(v).matches();
    }

    public static SemVer parse(String v) {
        Matcher m = VERSION.matcher(v == null ? "" : v.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a semantic version");
        }
        return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c == 0 ? Integer.compare(patch, o.patch) : c;
    }

    /** {@code true} when {@code version} satisfies every space-separated constraint of {@code range} (blank = any). */
    public static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank()) {
            return true;
        }
        SemVer v = parse(version);
        for (String part : range.strip().split("\\s+")) {
            Matcher m = CONSTRAINT.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("Invalid version range");
            }
            String op = m.group(1) == null ? "=" : m.group(1);
            int c = v.compareTo(parse(m.group(2)));
            boolean ok = switch (op) {
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case "<" -> c < 0;
                default -> c == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
