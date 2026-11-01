package com.wellnessz.e31.demo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.simulator.SimulatorService;
import com.wellnessz.e31.support.EngineSettings;

/**
 * Local / test profiles only: a few synthetic observations for the demo employees (contract section 19) – a vitals panel
 * and a spirometry session waiting for verification for {@code E1001} / {@code E1002}, and one unmatched reading with a
 * suggestion. They are fed through the real pipeline (virtual demo endpoint, sample drivers) because the canonical content
 * is field-encrypted and cannot be written by SQL. Nothing is routed automatically; idempotent across restarts.
 */
@Component("e31DeviceEngineDemoSeeder")
@Profile({"local", "test"})
public class DeviceEngineDemoSeeder {

    static final String ENDPOINT = "DEMO-SEEDER";
    static final List<String> DATASETS = List.of("vitals/csm-pcd01/normal", "spirometry/easyone-gdt/normal", "vitals/csm-pcd01/unknown-employee");
    private static final Logger log = LoggerFactory.getLogger(DeviceEngineDemoSeeder.class);

    private final IngestService ingest;
    private final GoldenDatasets golden;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DeviceEngineDemoSeeder(IngestService ingest, GoldenDatasets golden, JdbcTemplate jdbc,
                                  @Value("${wellnessz.device.demo-seed.enabled:true}") boolean enabled) {
        this.ingest = ingest;
        this.golden = golden;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!enabled) {
            return;
        }
        try {
            Integer existing = jdbc.queryForObject("select count(*) from deviceint.message where endpoint_id = ?", Integer.class, ENDPOINT);
            Integer employees = jdbc.queryForObject("select count(*) from masterdata.employee where employee_id in ('E1001','E1002')", Integer.class);
            if (existing != null && existing > 0 || employees == null || employees < 2) {
                return;
            }
            IngestService.Source source = new IngestService.Source(ENDPOINT, EngineSettings.DEFAULT_TENANT, "LHR", Set.of(), true);
            for (String id : DATASETS) {
                GoldenDatasets.Dataset d = golden.require(id);
                String kind = SimulatorService.KIND.get(d.adapter());
                ingest.acquire(new IngestService.Acquire(source, kind, d.contentType(), "DEMO:" + id, null, null, null, "SIM-" + d.adapter(), null,
                        false, golden.content(d.resource()), d.headers() == null ? Map.of() : d.headers(), false, false, d.packageId(), null, true));
            }
            log.info("E31 demo observations seeded");
        } catch (RuntimeException e) {
            log.warn("E31 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }
}
