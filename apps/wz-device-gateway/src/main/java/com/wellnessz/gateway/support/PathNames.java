package com.wellnessz.gateway.support;

import java.nio.file.Path;

/** Null-safe file name / parent helpers ({@link Path#getFileName()} and {@link Path#getParent()} may return null). */
public final class PathNames {

    private PathNames() {
    }

    public static String name(Path p) {
        Path f = p.getFileName();
        return f == null ? "" : f.toString();
    }

    public static Path parent(Path p) {
        Path abs = p.toAbsolutePath();
        Path parent = abs.getParent();
        return parent == null ? abs : parent;
    }
}
