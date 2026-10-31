package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.support.AtomicFiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Worklist push (E31-HLR-08, contract §16.1): pulls {@code GET /device-ingest/worklist} for a device instance and writes
 * one GDT 2.1 "Stammdaten anfordern / Untersuchung anfordern" record (Satzart 6302) per order into the vendor software's
 * import folder. The worklist is already minimised by the core to what the driver needs; the files live only in the
 * vendor's import folder, which the vendor software consumes.
 */
public class WorklistService {

    private static final DateTimeFormatter GDT_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final CoreClient core;
    private final GatewayProperties.Worklist cfg;
    private final JsonMapper json = JsonMapper.builder().build();

    public WorklistService(CoreClient core, GatewayProperties.Worklist cfg) {
        this.core = core;
        this.cfg = cfg;
    }

    /** Pulls and writes the worklist; returns the number of GDT files written. */
    public int push(String deviceInstanceId, LocalDate date) throws IOException {
        if (cfg.gdtFolder() == null) {
            throw new IllegalStateException("worklist push needs wz.gateway.worklist.gdt-folder");
        }
        if (deviceInstanceId == null || !deviceInstanceId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("deviceInstanceId required");
        }
        String query = "?deviceInstanceId=" + URLEncoder.encode(deviceInstanceId, StandardCharsets.UTF_8)
                + "&date=" + date;
        CoreResponse r = core.send("GET", "/device-ingest/worklist" + query, null, Map.of(), null);
        if (!r.success()) {
            throw new IOException("worklist pull refused: HTTP " + r.status());
        }
        int n = 0;
        Files.createDirectories(cfg.gdtFolder());
        for (JsonNode e : json.readTree(r.text())) {
            String orderRef = e.path("orderRef").asString("");
            if (!orderRef.matches("[A-Za-z0-9._-]{1,40}")) {
                continue;
            }
            Path target = cfg.gdtFolder().resolve("WZ_" + orderRef + ".gdt");
            AtomicFiles.writeBytes(target, gdt6302(e));
            n++;
        }
        return n;
    }

    /** Builds one GDT 2.1 record of Satzart 6302 (ISO-8859-1, field 9206 = 3). */
    byte[] gdt6302(JsonNode e) {
        StringBuilder body = new StringBuilder();
        String[] names = e.path("displayName").asString("").trim().split("\\s+", 2);
        String first = names.length > 1 ? names[0] : "";
        String last = names.length > 1 ? names[1] : names[0];
        body.append(line("8315", cfg.gdtReceiverId()));
        body.append(line("8316", cfg.gdtSenderId()));
        body.append(line("9206", "3"));
        body.append(line("9218", "02.10"));
        body.append(line("8310", e.path("orderRef").asString("")));
        body.append(line("3000", e.path("employeeId").asString("")));
        body.append(line("3101", last));
        body.append(line("3102", first));
        String dob = e.path("dateOfBirth").asString(null);
        if (dob != null && dob.matches("\\d{4}-\\d{2}-\\d{2}")) {
            body.append(line("3103", LocalDate.parse(dob).format(GDT_DATE)));
        }
        String sex = e.path("sex").asString("");
        if ("M".equalsIgnoreCase(sex) || "MALE".equalsIgnoreCase(sex)) {
            body.append(line("3110", "1"));
        } else if ("F".equalsIgnoreCase(sex) || "FEMALE".equalsIgnoreCase(sex)) {
            body.append(line("3110", "2"));
        }
        String head = line("8000", "6302");
        // 8100 = total record length in bytes (5 digits), including its own line
        int total = head.length() + body.length() + 3 + 4 + 5 + 2;
        String record = head + line("8100", String.format("%05d", total)) + body;
        return record.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** GDT line: 3-digit length (whole line incl. CR LF) + 4-digit field id + content + CR LF. */
    static String line(String field, String value) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\n]", " ");
        int len = 3 + 4 + clean.length() + 2;
        return String.format("%03d", len) + field + clean + "\r\n";
    }
}
