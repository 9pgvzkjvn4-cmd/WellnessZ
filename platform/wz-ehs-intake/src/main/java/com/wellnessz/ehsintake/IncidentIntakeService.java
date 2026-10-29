package com.wellnessz.ehsintake;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.ehsintake.excel.ExcelIncidentParser;
import com.wellnessz.ehsintake.model.EhsIncident;
import com.wellnessz.ehsintake.model.EhsIncidentRepository;
import com.wellnessz.ehsintake.model.ImportBatch;
import com.wellnessz.ehsintake.model.ImportBatchRepository;
import com.wellnessz.ehsintake.model.IncidentReceived;
import com.wellnessz.ehsintake.model.IncidentRecord;
import com.wellnessz.ehsintake.model.IntakeChannel;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.audit.AuditService;

/** Common ingestion path for every EHS channel: de-duplicate, persist, emit {@code ehs.incident.received.v1}. */
@Service
public class IncidentIntakeService {

    private final EhsIncidentRepository incidents;
    private final ImportBatchRepository batches;
    private final ExcelIncidentParser parser;
    private final EventBus bus;
    private final AuditService audit;
    private final Clock clock;
    private final String sourceSystem;

    public IncidentIntakeService(EhsIncidentRepository incidents, ImportBatchRepository batches, ExcelIncidentParser parser,
                                 EventBus bus, AuditService audit, Clock clock,
                                 @Value("${wellnessz.ehs.source-system:EHS}") String sourceSystem) {
        this.incidents = incidents;
        this.batches = batches;
        this.parser = parser;
        this.bus = bus;
        this.audit = audit;
        this.clock = clock;
        this.sourceSystem = sourceSystem;
    }

    @Transactional
    public ImportBatch importExcel(byte[] content, String fileName, IntakeChannel channel, String actor) {
        ExcelIncidentParser.ParseResult result;
        try {
            result = parser.parse(new ByteArrayInputStream(content), sourceSystem);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_workbook", e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("invalid_workbook", "Workbook could not be read");
        }
        ImportBatch batch = batches.save(new ImportBatch(channel, safeName(fileName), sha256(content), actor, clock.instant()));
        int[] counts = ingest(result.records(), channel, batch);
        String errors = result.errors().stream().limit(200).map(e -> "row " + e.row() + ": " + e.message())
                .collect(Collectors.joining("\n"));
        batch.complete(result.totalRows(), counts[0], counts[1], result.errors().size(), errors.isEmpty() ? null : errors);
        audit.record("EHS_IMPORT", "IMPORT_BATCH", batch.getId().toString(), AuditService.Outcome.SUCCESS,
                channel + " accepted=" + counts[0] + " duplicate=" + counts[1] + " rejected=" + result.errors().size());
        return batch;
    }

    @Transactional
    public ImportBatch importRecords(List<IncidentRecord> records, String sourceName, IntakeChannel channel, String actor) {
        ImportBatch batch = batches.save(new ImportBatch(channel, safeName(sourceName), null, actor, clock.instant()));
        int[] counts = ingest(records, channel, batch);
        batch.complete(records.size(), counts[0], counts[1], 0, null);
        audit.record("EHS_IMPORT", "IMPORT_BATCH", batch.getId().toString(), AuditService.Outcome.SUCCESS,
                channel + " accepted=" + counts[0] + " duplicate=" + counts[1]);
        return batch;
    }

    private int[] ingest(List<IncidentRecord> records, IntakeChannel channel, ImportBatch batch) {
        int accepted = 0;
        int duplicates = 0;
        for (IncidentRecord r : records) {
            if (incidents.existsBySourceSystemAndExternalRefAndEmployeeId(r.sourceSystem(), r.externalRef(), r.employeeId())) {
                duplicates++;
                continue;
            }
            EhsIncident incident = incidents.save(new EhsIncident(r, channel, batch.getId(), clock.instant()));
            bus.publish(new IncidentReceived(incident.getId().toString(), r.externalRef(), r.employeeId(), r.employeeName(),
                    r.orgUnitCode(), r.incidentType(), r.severity(), r.injury(), r.incidentClass(), r.bodyPart(), r.lostTime(),
                    r.exposureType(), r.unitCode(), r.location(), r.shift(),
                    r.occurredAt() == null ? null : r.occurredAt().toString(), r.reporterEmail()));
            accepted++;
        }
        return new int[]{accepted, duplicates};
    }

    private static String safeName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll("[^A-Za-z0-9._ \\-@<>]", "_");
        return n.length() > 300 ? n.substring(0, 300) : n;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
