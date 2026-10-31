package com.wellnessz.gateway.connector.dicom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal DICOM Part 10 header reader producing a PS3.18 (DICOM JSON model) dataset with the identifying attributes
 * the engine needs (SOP / study / series UIDs, modality, dates, device and patient identifiers). No pixel data is read.
 * Supports explicit and implicit VR little endian (the encodings used for the data set header of practically every
 * modality export, including compressed transfer syntaxes). Anything else → only the file meta information.
 * dcm4che is not on Maven Central (contract §0); modalities that export a JSON sidecar take precedence.
 */
public final class DicomMetadata {

    private static final String IMPLICIT_LE = "1.2.840.10008.1.2";
    private static final String EXPLICIT_BE = "1.2.840.10008.1.2.2";
    private static final Set<String> LONG_VR = Set.of("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "SV", "UC", "UN", "UR",
            "UT", "UV");
    private static final int UNDEFINED = -1;

    /** Attributes copied into the metadata (tag → VR used for implicit VR data sets). */
    private static final Map<Integer, String> WANTED = Map.ofEntries(
            Map.entry(0x00080016, "UI"), Map.entry(0x00080018, "UI"), Map.entry(0x00080020, "DA"),
            Map.entry(0x00080030, "TM"), Map.entry(0x00080050, "SH"), Map.entry(0x00080060, "CS"),
            Map.entry(0x00080070, "LO"), Map.entry(0x00081090, "LO"), Map.entry(0x00100010, "PN"),
            Map.entry(0x00100020, "LO"), Map.entry(0x00100030, "DA"), Map.entry(0x00100040, "CS"),
            Map.entry(0x00181000, "LO"), Map.entry(0x0020000D, "UI"), Map.entry(0x0020000E, "UI"),
            Map.entry(0x00200011, "IS"), Map.entry(0x00200013, "IS"));

    private DicomMetadata() {
    }

    public static boolean isPart10(byte[] data) {
        return data.length > 132 && data[128] == 'D' && data[129] == 'I' && data[130] == 'C' && data[131] == 'M';
    }

    /** Returns the PS3.18 JSON dataset; never throws on malformed input (returns what could be read). */
    public static ObjectNode extract(byte[] data, JsonMapper json) {
        Map<Integer, String[]> values = new TreeMap<>();
        Map<Integer, String> vrs = new TreeMap<>();
        if (isPart10(data)) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(132);
            String transferSyntax = null;
            try {
                // file meta information (group 0002) is always explicit VR little endian
                while (buf.remaining() >= 8 && (buf.getShort(buf.position()) & 0xFFFF) == 0x0002) {
                    Element e = readElement(buf, true);
                    if (e == null) {
                        break;
                    }
                    if (e.tag == 0x00020010) {
                        transferSyntax = string(data, e.offset, e.length);
                    }
                    skip(buf, e, true);
                }
                if (!EXPLICIT_BE.equals(transferSyntax)) {
                    boolean explicit = !IMPLICIT_LE.equals(transferSyntax);
                    while (buf.remaining() >= 8) {
                        Element e = readElement(buf, explicit);
                        if (e == null || e.tag >= 0x7FE00010 || e.tag > 0x00210000) {
                            break; // pixel data or past the identifying groups
                        }
                        if (WANTED.containsKey(e.tag) && e.length != UNDEFINED) {
                            values.put(e.tag, string(data, e.offset, e.length).split("\\\\"));
                            vrs.put(e.tag, explicit ? e.vr : WANTED.get(e.tag));
                        }
                        skip(buf, e, explicit);
                    }
                }
            } catch (RuntimeException ex) {
                // malformed or truncated: keep what was read
            }
        }
        ObjectNode ds = json.createObjectNode();
        values.forEach((tag, v) -> {
            ObjectNode attr = ds.putObject(String.format("%08X", tag));
            String vr = vrs.get(tag);
            attr.put("vr", vr);
            ArrayNode arr = attr.putArray("Value");
            for (String s : v) {
                if ("PN".equals(vr)) {
                    arr.addObject().put("Alphabetic", s);
                } else if ("IS".equals(vr)) {
                    try {
                        arr.add(Long.parseLong(s.trim()));
                    } catch (NumberFormatException nfe) {
                        arr.add(s);
                    }
                } else {
                    arr.add(s);
                }
            }
        });
        return ds;
    }

    private record Element(int tag, String vr, int length, int offset) {
    }

    private static Element readElement(ByteBuffer buf, boolean explicit) {
        int group = buf.getShort() & 0xFFFF;
        int element = buf.getShort() & 0xFFFF;
        int tag = (group << 16) | element;
        if (group == 0xFFFE) { // item / delimitation items carry a 4-byte length and no VR
            return new Element(tag, null, buf.getInt(), buf.position());
        }
        String vr = null;
        int length;
        if (explicit) {
            vr = new String(new byte[] {buf.get(), buf.get()}, StandardCharsets.US_ASCII);
            if (LONG_VR.contains(vr)) {
                buf.getShort();
                length = buf.getInt();
            } else {
                length = buf.getShort() & 0xFFFF;
            }
        } else {
            length = buf.getInt();
            if (length == UNDEFINED) {
                vr = "SQ";
            }
        }
        return new Element(tag, vr, length, buf.position());
    }

    /** Skips the value; undefined-length sequences / items are walked until their delimitation item. */
    private static void skip(ByteBuffer buf, Element e, boolean explicit) {
        if (e.length != UNDEFINED) {
            if (e.length < 0 || e.length > buf.remaining()) {
                throw new IllegalStateException("element length out of range");
            }
            buf.position(buf.position() + e.length);
            return;
        }
        int depth = 1;
        while (depth > 0 && buf.remaining() >= 8) {
            Element inner = readElement(buf, explicit);
            if (inner.tag == 0xFFFEE0DD || inner.tag == 0xFFFEE00D) {
                depth--; // sequence / item delimitation
            } else if (inner.length == UNDEFINED) {
                depth++;
            } else {
                if (inner.length < 0 || inner.length > buf.remaining()) {
                    throw new IllegalStateException("element length out of range");
                }
                buf.position(buf.position() + inner.length);
            }
        }
    }

    private static String string(byte[] data, int offset, int length) {
        int end = offset + length;
        while (end > offset && (data[end - 1] == 0 || data[end - 1] == ' ')) {
            end--;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    }
}
