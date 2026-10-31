package com.wellnessz.gateway.connector.file;

import com.wellnessz.gateway.support.PathNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.queue.ItemKind;

/**
 * P2 vendor export watcher: CSV, XML, GDT and PDF exports are forwarded one file per request to
 * {@code POST /device-ingest/files} with {@code X-File-Name}, {@code X-File-Format}, {@code X-File-Sha256} and
 * {@code X-Source-Path-Hash} (hash of the watched folder – never the path). PDF pairing with its data file is done
 * centrally by the driver's attachment rule.
 */
public class FileWatcher extends PolledFolderConnector {

    private final String gdtCharset;

    public FileWatcher(GatewayProperties.Watcher cfg, IngestSink sink, Clock clock, Path dataDir) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock, cfg.path(), cfg.pattern(), cfg.archive(),
                cfg.quarantine(), cfg.postAction(), cfg.stabilitySeconds(), cfg.maxFileBytes(), cfg.pollInterval(),
                dataDir);
        this.gdtCharset = cfg.gdtCharset();
    }

    @Override
    public String type() {
        return "FOLDER";
    }

    /** Export formats accepted at the edge. */
    enum Format {
        CSV("text/csv; charset=UTF-8"), XML("application/xml"), GDT("text/plain"), PDF("application/pdf");

        final String contentType;

        Format(String contentType) {
            this.contentType = contentType;
        }

        static Format of(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".csv")) {
                return CSV;
            }
            if (n.endsWith(".xml")) {
                return XML;
            }
            if (n.endsWith(".gdt")) {
                return GDT;
            }
            if (n.endsWith(".pdf")) {
                return PDF;
            }
            return null;
        }
    }

    @Override
    protected Decision process(Path file, byte[] content, String sha256) {
        Format format = Format.of(PathNames.name(file));
        if (format == null) {
            return Decision.quarantine("unsupported_format");
        }
        String invalid = validate(format, content);
        if (invalid != null) {
            return Decision.quarantine(invalid);
        }
        String contentType = format == Format.GDT ? "text/plain; charset=" + gdtCharset : format.contentType;
        Map<String, String> headers = Map.of(
                "X-File-Name", headerSafe(PathNames.name(file)),
                "X-File-Format", format.name(),
                "X-File-Sha256", sha256,
                "X-Source-Path-Hash", sourcePathHash);
        sink.capture(type(), ItemKind.FILE, deviceInstanceId, contentType, headers, content, sha256);
        return Decision.queued();
    }

    /** Cheap structural checks so obviously broken exports are quarantined at the edge. */
    static String validate(Format format, byte[] content) {
        switch (format) {
            case PDF -> {
                return startsWith(content, "%PDF-") ? null : "pdf_invalid";
            }
            case XML -> {
                String head = head(content);
                if (head.startsWith("ï»¿")) {
                    head = head.substring(3); // UTF-8 byte order mark
                }
                return head.stripLeading().startsWith("<") ? null : "xml_invalid";
            }
            case GDT -> {
                // GDT 2.1/3.x: each line "LLLFFFFcontent", first record field 8000 (Satzart)
                String head = head(content);
                return head.length() >= 7 && head.substring(0, 3).chars().allMatch(Character::isDigit)
                        && "8000".equals(head.substring(3, 7)) ? null : "gdt_invalid";
            }
            case CSV -> {
                return head(content).contains("\u0000") ? "csv_invalid" : null;
            }
            default -> {
                return "unsupported_format";
            }
        }
    }

    private static boolean startsWith(byte[] content, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (content.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (content[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static String head(byte[] content) {
        return new String(content, 0, Math.min(content.length, 512), StandardCharsets.ISO_8859_1);
    }
}
