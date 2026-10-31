package com.wellnessz.e32.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.integration.JobRunGuard;

/**
 * Idempotent seed of the 133-model device inventory (contract §5, E32-BL-001): upsert by inventory id, {@code seed_version}
 * = SHA-256 of the file, guarded by {@link JobRunGuard} (one instance per file version), never overwrites fields an
 * administrator edited in the console ({@code modified_fields} / {@code locally_modified}). Seeded models start as
 * {@code CANDIDATE} with posture {@code NOT_ASSESSED}.
 */
@Component("e32CatalogueSeedLoader")
@Order(10)
public class CatalogueSeedLoader implements ApplicationRunner {

    public static final String RESOURCE = "e32/device-inventory.json";
    static final String SEED_NAME = "device-inventory";
    private static final Logger log = LoggerFactory.getLogger(CatalogueSeedLoader.class);

    private final E32Support support;
    private final JobRunGuard guard;
    private final TransactionTemplate tx;

    public CatalogueSeedLoader(E32Support support, JobRunGuard guard, TransactionTemplate tx) {
        this.support = support;
        this.guard = guard;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Result r = load();
            if (r != null) {
                log.info("Device catalogue seed {}: {} inserted, {} updated", r.sha256().substring(0, 12), r.inserted(), r.updated());
            }
        } catch (RuntimeException | IOException e) {
            log.error("Device catalogue seed failed: {}", e.getClass().getSimpleName());
        }
    }

    public record Result(String sha256, int inserted, int updated, int total) {
    }

    /** Loads the inventory when its hash differs from the recorded seed version; {@code null} when already current. */
    public Result load() throws IOException {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            bytes = in.readAllBytes();
        }
        String sha = Docs.sha256(bytes);
        List<String> current = support.jdbc().queryForList("select sha256 from devicecat.seed_state where seed_name = ?", String.class, SEED_NAME);
        if (!current.isEmpty() && current.get(0).equals(sha)) {
            return null;
        }
        List<Object> rows = support.readList(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        Result result = tx.execute(s -> {
            // one instance seeds at a time; the others see the recorded seed version and skip
            Boolean locked = support.jdbc().queryForObject("select pg_try_advisory_xact_lock(hashtext('e32-catalogue-seed'))", Boolean.class);
            if (!Boolean.TRUE.equals(locked) || !support.jdbc().queryForList(
                    "select 1 from devicecat.seed_state where seed_name = ? and sha256 = ?", Integer.class, SEED_NAME, sha).isEmpty()) {
                return null;
            }
            int inserted = 0;
            int updated = 0;
            for (Object o : rows) {
                if (upsert(Docs.map(o), sha)) {
                    inserted++;
                } else {
                    updated++;
                }
            }
            Timestamp now = Timestamp.from(support.now());
            if (support.jdbc().update("update devicecat.seed_state set sha256 = ?, models = ?, loaded_at = ? where seed_name = ?",
                    sha, rows.size(), now, SEED_NAME) == 0) {
                support.jdbc().update("insert into devicecat.seed_state (seed_name, sha256, models, loaded_at) values (?, ?, ?, ?)",
                        SEED_NAME, sha, rows.size(), now);
            }
            return new Result(sha, inserted, updated, rows.size());
        });
        if (result != null) {
            guard.tryStart("e32-catalogue-seed", sha.substring(0, 32)); // run record of this seed version (platform.scheduled_job_run)
        }
        return result;
    }

    /** @return true when inserted, false when updated */
    boolean upsert(Map<String, Object> row, String sha) {
        String id = Docs.str(row.get("id"));
        var family = InventoryMapping.family(Docs.str(row.get("family")));
        List<String> options = InventoryMapping.options(Docs.strings(row.get("options")));
        Map<String, Object> detail = InventoryMapping.detail(row);
        Timestamp now = Timestamp.from(support.now());
        List<Map<String, Object>> existing = support.jdbc().queryForList(
                "select modified_fields, detail from devicecat.model where inventory_id = ?", id);
        if (existing.isEmpty()) {
            support.jdbc().update("""
                    insert into devicecat.model (model_code, inventory_id, family, family_label, manufacturer, model, oh_use, connectivity, protocol,
                        fda_codes, sprint, confidence, options, detail, lifecycle, posture, locally_modified, modified_fields, seed_version,
                        created_by, created_at, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 'NOT_ASSESSED', false, '', ?, 'seed', ?, 'seed', ?)""",
                    id, id, family.name(), Docs.str(row.get("family")), Docs.str(row.get("manufacturer")), Docs.str(row.get("model")),
                    trim(row.get("ohUse"), 1000), trim(row.get("connectivity"), 1000), trim(row.get("protocol"), 1000), trim(row.get("fdaCodes"), 300),
                    Docs.str(row.get("sprint")), Docs.str(row.get("confidence")), Docs.joinCsv(options), support.write(detail), sha, now, now);
            return true;
        }
        Set<String> modified = Set.copyOf(Docs.csv(Docs.str(existing.get(0).get("modified_fields"))));
        Map<String, Object> oldDetail = support.readMap(Docs.str(existing.get(0).get("detail")));
        for (String key : List.copyOf(detail.keySet())) {
            if (modified.contains(key) && oldDetail.containsKey(key)) {
                detail.put(key, oldDetail.get(key));
            }
        }
        // keep console-maintained detail members the inventory does not carry (UDI, validation flag …)
        oldDetail.forEach(detail::putIfAbsent);
        support.jdbc().update("""
                update devicecat.model set
                    manufacturer = case when ? then manufacturer else ? end,
                    model = case when ? then model else ? end,
                    oh_use = case when ? then oh_use else ? end,
                    connectivity = case when ? then connectivity else ? end,
                    protocol = case when ? then protocol else ? end,
                    fda_codes = case when ? then fda_codes else ? end,
                    options = case when ? then options else ? end,
                    sprint = ?, confidence = ?, detail = ?, seed_version = ?, updated_at = ?, row_version = row_version + 1
                where inventory_id = ?""",
                modified.contains("manufacturer"), Docs.str(row.get("manufacturer")),
                modified.contains("model"), Docs.str(row.get("model")),
                modified.contains("ohUse"), trim(row.get("ohUse"), 1000),
                modified.contains("connectivity"), trim(row.get("connectivity"), 1000),
                modified.contains("protocol"), trim(row.get("protocol"), 1000),
                modified.contains("fdaCodes"), trim(row.get("fdaCodes"), 300),
                modified.contains("options"), Docs.joinCsv(options),
                Docs.str(row.get("sprint")), Docs.str(row.get("confidence")), support.write(detail), sha, now, id);
        return false;
    }

    private static String trim(Object o, int max) {
        String s = Docs.str(o);
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
