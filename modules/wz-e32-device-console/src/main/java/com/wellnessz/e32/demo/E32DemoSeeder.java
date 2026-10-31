package com.wellnessz.e32.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.config.CertificationService;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationService;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.config.WizardRules;
import com.wellnessz.e32.driver.DriverRegistryService;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.option.OptionPacks;
import com.wellnessz.e32.option.ParameterValidator;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/**
 * Local / test profiles only (synthetic): registers the bundled driver packages ({@code e32/demo-drivers.json}, signed through
 * E34 when deployed) and creates the published demo configurations of contract §19 – spirometry (P2 GDT), audiometry
 * (P2 database), vitals (P4 MLLP), vitals BLE (P3 tablet), breath alcohol (P1 serial), POCT (P5 data manager), ECG (P4 HL7),
 * a quarantined DXB monitor, the locked-out spirometer (expired 3-L syringe check) and a draft paused at step 4 – each with
 * a certification record and PDF. Idempotent: does nothing once {@code DEV-000001} exists.
 */
@Component("e32E32DemoSeeder")
@Profile({"local", "test"})
public class E32DemoSeeder {

    public static final String DRIVERS = "e32/demo-drivers.json";
    private static final Logger log = LoggerFactory.getLogger(E32DemoSeeder.class);
    private static final Set<String> DEMO_MODELS = Set.of("MD-001", "MD-002", "MD-015", "MD-027", "MD-037", "MD-045", "MD-081", "MD-089", "MD-097",
            "MD-105", "MD-121", "MD-125", "MD-133");

    private final E32Support support;
    private final DriverRegistryService registry;
    private final DriverStore drivers;
    private final ModelStore models;
    private final ConfigurationStore store;
    private final ConfigurationService configurations;
    private final CertificationService certifications;
    private final OptionPacks packs;
    private final TransactionTemplate tx;

    public E32DemoSeeder(E32Support support, DriverRegistryService registry, DriverStore drivers, ModelStore models, ConfigurationStore store,
                         ConfigurationService configurations, CertificationService certifications, OptionPacks packs, TransactionTemplate tx) {
        this.support = support;
        this.registry = registry;
        this.drivers = drivers;
        this.models = models;
        this.store = store;
        this.configurations = configurations;
        this.certifications = certifications;
        this.packs = packs;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            if (store.live("DEV-000001").isPresent() || store.latest("CFG-000001").isPresent()) {
                return;
            }
            if (models.find("MD-001").isEmpty()) {
                log.info("E32 demo seed skipped: catalogue not seeded");
                return;
            }
            int registered = registerDrivers();
            tx.executeWithoutResult(s -> {
                Boolean locked = support.jdbc().queryForObject("select pg_try_advisory_xact_lock(hashtext('e32-demo-seed'))", Boolean.class);
                if (!Boolean.TRUE.equals(locked) || store.latest("CFG-000001").isPresent()) {
                    return;
                }
                postures();
                instances();
            });
            log.info("E32 demo seed: {} driver packages registered, demo configurations published", registered);
        } catch (RuntimeException | IOException e) {
            log.warn("E32 demo seed failed: {}", e.getClass().getSimpleName());
        }
    }

    private int registerDrivers() throws IOException {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(DRIVERS).getInputStream()) {
            bytes = in.readAllBytes();
        }
        int n = 0;
        for (Object o : support.readList(new String(bytes, StandardCharsets.UTF_8))) {
            Map<String, Object> pkg = Docs.map(o);
            if (registry.registerBundled("demo-seed", pkg, "CERTIFIED").isPresent()) {
                n++;
            }
        }
        return n;
    }

    private void postures() {
        for (String code : DEMO_MODELS) {
            models.find(code).ifPresent(m -> {
                Map<String, Object> detail = Docs.copyMap(m.detail());
                detail.put("security", Docs.of("mds2", Docs.of("received", true, "version", "HN 1-2019", "documentId", "DEMO-MDS2-" + code),
                        "sbom", Docs.of("available", true, "format", "CYCLONEDX", "documentId", "DEMO-SBOM-" + code), "supportStatus", "SUPPORTED",
                        "osSupported", true, "patchPath", "VENDOR", "remoteSupport", "BROKERED",
                        "contacts", Docs.of("securityEmail", "psirt@vendor.example", "disclosureUrl", "https://vendor.example/security"),
                        "evaluatedPosture", "ACCEPTABLE", "assessedBy", "demo-seed"));
                models.updateDetail(m, detail, Set.of("security"), "demo-seed");
                models.setPosture(code, "ACCEPTABLE", "demo-seed");
                boolean certified = drivers.forModel(code).stream().anyMatch(d -> "CERTIFIED".equals(d.status()));
                if (certified) {
                    models.setLifecycle(code, "CERTIFIED", "demo-seed");
                }
            });
        }
    }

    private record Spec(String n, String model, String option, String site, String station, String endpoint, String endpointKind, String serial,
                        String equipment, String packageId, String version, List<String> association, Map<String, Object> parameters, String status) {
    }

    private void instances() {
        String agent = "EP-LHR-AGT-01";
        String gateway = "EP-LHR-GW-01";
        List<Spec> specs = List.of(
                new Spec("1", "MD-001", "P2", "LHR", "LHR-OH-ROOM-2", agent, "DEVICE_HOST_AGENT", "EA-2209-4411", "SPIRO-HQ-01",
                        "spiro.ndd.easyone-connect-gdt", "1.2.0", List.of("WORKLIST", "SESSION", "VENDOR_ID_MATCH"),
                        Docs.of("source", "FOLDER_WATCH", "path", "C:\\EasyOneConnect\\Export", "pattern", "*.gdt", "encoding", "ISO-8859-1", "pollSeconds", 10,
                                "stabilitySeconds", 5, "afterRead", "KEEP", "attachmentRule", Docs.of("pairBy", "STEM", "extensions", List.of("pdf")),
                                "hostEndpointId", agent), Configuration.PUBLISHED),
                new Spec("2", "MD-015", "P2", "LHR", "LHR-OH-BOOTH-1", agent, "DEVICE_HOST_AGENT", "AU300-1001", "AUD-LHR-01",
                        "audio.benson.cca200-db", "1.0.0", List.of("WORKLIST", "SESSION", "VENDOR_ID_MATCH"),
                        Docs.of("source", "DB_QUERY", "dialect", "SQLSERVER", "connection", Docs.of("host", "benson-pc.lhr.example", "port", 1433, "database", "Benson"),
                                "dbCredentialRef", "vault:device/lhr/benson-readonly", "query", "select * from v_wz_audiogram where modified_at > :watermark",
                                "watermarkColumn", "modified_at", "pollSeconds", 30, "hostEndpointId", agent), Configuration.PUBLISHED),
                new Spec("3", "MD-027", "P4", "LHR", "LHR-OH-ROOM-1", gateway, "GATEWAY", "CSM-100042", null, "vitals.welchallyn.csm-pcd01", "1.0.0",
                        List.of("WORKLIST", "SESSION", "VENDOR_ID_MATCH"), mllp("10.20.30.41", gateway, 2575, "CONNEX"), Configuration.PUBLISHED),
                new Spec("4", "MD-037", "P3", "LHR", "LHR-OH-ROOM-1", "EP-LHR-TAB-01", "MOBILE_APP", "UA651-77310", null, "vitals.and.ua651ble-blp", "1.0.0",
                        List.of("SESSION", "BADGE"),
                        Docs.of("transport", "BLE", "pairing", "LE_SECURE_CONNECTIONS_PASSKEY", "bonding", "REQUIRED", "bondingOwner", "TABLET",
                                "bondingOwnerEndpointId", "EP-LHR-TAB-01", "service", "0x1810", "characteristics", List.of("0x2A35"), "indications", true,
                                "peripheralAllowList", List.of(Docs.of("name", "UA-651BLE", "addressHash", Docs.sha256("demo-peripheral-ua651ble"))),
                                "reconnect", "AUTO", "rssiMinDbm", -80), Configuration.PUBLISHED),
                new Spec("5", "MD-097", "P1", "LHR", "LHR-OH-ROOM-1", agent, "DEVICE_HOST_AGENT", "FC20-55021", null, "da.lifeloc.fc20-serial", "1.0.0",
                        List.of("WORKLIST", "SESSION"),
                        Docs.of("connection", "RS232", "port", "COM3", "baud", 9600, "dataBits", 8, "parity", "NONE", "stopBits", 1, "flowControl", "NONE",
                                "framing", Docs.of("type", "LINE", "terminator", "\r\n", "maxFrameBytes", 4096), "mode", "PUSH", "hostEndpointId", agent),
                        Configuration.PUBLISHED),
                new Spec("6", "MD-081", "P5", "LHR", "LHR-OH-ROOM-2", gateway, "DATA_MANAGER", "AF2-30871", null, "poc.abbott.afinion2-poct1a", "1.0.0",
                        List.of("SESSION", "VENDOR_ID_MATCH"),
                        Docs.of("dms", Docs.of("vendor", "INFO_HQ", "endpoint", gateway, "deviceIds", List.of("AF2-30871"), "dmsId", "INFOHQ-LHR"),
                                "transport", "POCT1A_XML", "operatorList", "SYNC_FROM_DMS",
                                "qcLotPolicy", Docs.of("requirePassingQc", true, "qcWindowHours", 24, "newLotRequiresQc", true)), Configuration.PUBLISHED),
                new Spec("7", "MD-045", "P4", "LHR", "LHR-OH-ROOM-1", gateway, "GATEWAY", "MAC55-90112", "ECG-HQ-01", "ecg.ge.mac-hl7", "1.0.0",
                        List.of("WORKLIST", "SESSION", "VENDOR_ID_MATCH"), mllp("10.20.30.45", gateway, 2576, "MUSE"), Configuration.PUBLISHED),
                new Spec("8", "MD-027", "P4", "DXB", "DXB-OUT-ROOM-1", "EP-DXB-GW-01", "GATEWAY", "CSM-100099", null, "vitals.welchallyn.csm-pcd01", "1.0.0",
                        List.of("SESSION", "VENDOR_ID_MATCH"), mllp("10.40.10.21", "EP-DXB-GW-01", 2575, "CONNEX"), Configuration.QUARANTINED));
        for (Spec s : specs) {
            published(s);
        }
        pausedDraft();
        qcHistory();
    }

    private static Map<String, Object> mllp(String ip, String listener, int port, String app) {
        return Docs.of("deviceAddress", Docs.of("ip", ip), "mllp", Docs.of("listenerEndpointId", listener, "port", port, "tls", "REQUIRED", "tlsVersion", "1.3",
                        "cipherPolicy", "MODERN", "clientCertRequired", true),
                "msh", Docs.of("sendingApplication", app, "sendingFacility", "OH_CLINIC", "receivingApplication", "WELLNESSZ", "receivingFacility", "WZ"),
                "ackMode", "ORIGINAL", "messageProfile", "IHE_PCD_01");
    }

    private void published(Spec s) {
        String id = "CFG-00000" + s.n();
        String instance = "DEV-00000" + s.n();
        Configuration c = ConfigurationStore.draft(id, 1, instance, s.model(), "deviceadmin1");
        ModelStore.ModelRow m = models.require(s.model());
        Map<String, Object> manifest = drivers.find(s.packageId(), s.version()).map(d -> support.readMap(d.manifestJson())).orElseThrow();
        Instant now = support.now();
        Instant created = now.minus(Duration.ofDays(20));
        c.setOption(s.option());
        c.setSiteCode(s.site());
        c.setStationCode(s.station());
        c.setRoomCode(s.station());
        c.setEndpointId(s.endpoint());
        c.setEndpointKind(s.endpointKind());
        c.setZone("DEVICE_VLAN");
        c.setSerial(s.serial());
        c.setEquipmentDeviceId(s.equipment());
        c.setFirmware("1.0.0");
        c.setDriverPackageId(s.packageId());
        c.setDriverVersion(s.version());
        c.setStep(1, "VALID", Docs.of("modelCode", s.model(), "serial", s.serial(), "equipmentDeviceId", s.equipment(), "lifecycle", m.lifecycle(),
                "posture", "ACCEPTABLE"), null, "deviceadmin1", created);
        c.setStep(2, "VALID", Docs.of("option", s.option(), "driver", Docs.of("packageId", s.packageId(), "version", s.version(), "status", "CERTIFIED")),
                null, "deviceadmin1", created);
        List<Map<String, Object>> items = WizardRules.prerequisites(packs.prerequisites(s.option()), m.family(), m.highConfidence(), s.option());
        for (Map<String, Object> i : items) {
            if (!"NOT_APPLICABLE".equals(i.get("status"))) {
                i.put("status", "DONE");
                i.put("evidence", Docs.of("reference", "NETWORK_ZONE".equals(i.get("code")) ? "DEVICE_VLAN" : "DEMO-EVIDENCE", "text", "Synthetic demo evidence"));
            }
        }
        c.setStep(3, "VALID", Docs.of("items", items), null, "deviceadmin1", created);
        Map<String, Object> common = ParameterValidator.defaults(packs.commonSchema());
        common.put("driverPackageId", s.packageId());
        common.put("driverVersion", s.version());
        common.put("timeZone", "DXB".equals(s.site()) ? "Asia/Dubai" : "Europe/London");
        c.setStep(4, "VALID", Docs.of("parameters", s.parameters(), "common", common), null, "deviceadmin1", created);
        List<Object> bindings = WizardRules.completeBindings(new ArrayList<>(Docs.list(Docs.copy(manifest.get("bindings")))), manifest);
        List<String> forms = new ArrayList<>();
        bindings.forEach(b -> forms.add(Docs.str(Docs.map(b).get("formCode"))));
        c.setStep(5, "VALID", Docs.of("associationMethods", s.association(), "siteCode", s.site(), "roomCode", s.station(), "stationCode", s.station(),
                "services", List.of("PERIODIC_MEDICAL"), "formCodes", forms, "bindings", bindings), null, "deviceadmin1", created);
        List<Object> tests = c.tests();
        tests.add(test("SIMULATOR", created.plus(Duration.ofHours(1))));
        tests.add(test("LIVE", created.plus(Duration.ofHours(2))));
        c.data().put("nurseReview", Docs.of("outcome", "ACCEPTED", "note", "Test form values match the device display", "by", "nurse1",
                "at", created.plus(Duration.ofHours(3)).toString()));
        c.data().put("stepEditors", new ArrayList<>(List.of("deviceadmin1")));
        c.setStep(6, "VALID", Docs.of("tests", 2), null, "deviceadmin1", created);
        c.setSubmitted("deviceadmin1", created.plus(Duration.ofHours(4)));
        c.setApproved("deviceapprover1", created.plus(Duration.ofHours(5)));
        c.data().put("approvals", new ArrayList<>(List.of(Docs.of("role", "DEVICE_APPROVER", "by", "deviceapprover1",
                "at", created.plus(Duration.ofHours(5)).toString()))));
        Map<String, Object> record = certifications.certify(c, configurations.gates(c), "DEMO");
        c.setStep(7, "VALID", Docs.of("certificationId", record.get("certificationId"), "approvedBy", "deviceapprover1"), null, "deviceapprover1", created);
        c.setStep(8, "VALID", Docs.of("publishedBy", "deviceadmin1", "endpointId", s.endpoint()), null, "deviceadmin1", created);
        c.setPublished("deviceadmin1", created.plus(Duration.ofHours(6)));
        c.setCurrentStep(8);
        c.setStatus(s.status());
        if (Configuration.QUARANTINED.equals(s.status())) {
            c.setStatusReason("ANOMALY_FAILED_AUTH_BURST");
        }
        store.insert(c);
        support.history("CONFIGURATION", id, 1, "demo-seed", "DEMO_PUBLISHED", null, Docs.of("deviceInstanceId", instance), "DEMO");
        if (Configuration.QUARANTINED.equals(s.status())) {
            support.jdbc().update("""
                    insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                    values (?, ?, ?, 'QUARANTINED', 'CRITICAL', 'Endpoint isolated (demo)', ?, 'OPEN', ?) on conflict do nothing""",
                    UUID.randomUUID(), instance, s.site(), "QUARANTINED:" + instance, Timestamp.from(now));
        }
    }

    private Map<String, Object> test(String mode, Instant at) {
        Map<String, Object> t = new LinkedHashMap<>(Docs.of("testRunId", UUID.randomUUID().toString(), "mode", mode, "status", "PASSED",
                "datasetId", "normal", "steps", List.of(Docs.of("name", "CONNECTION", "outcome", "PASS"), Docs.of("name", "END_TO_END", "outcome", "PASS"),
                        Docs.of("name", "NEGATIVE_VECTORS", "outcome", "PASS")), "finishedAt", at.toString(), "by", "deviceadmin1"));
        t.put("evidenceSha256", Docs.sha256(support.write(t)));
        return t;
    }

    /** A fit-tester configuration paused at step 4 (resume-and-continue demo). */
    private void pausedDraft() {
        ModelStore.ModelRow m = models.require("MD-105");
        Configuration c = ConfigurationStore.draft("CFG-000009", 1, "DEV-000009", "MD-105", "deviceadmin1");
        Instant now = support.now();
        c.setSiteCode("LHR");
        c.setOption("P6");
        c.setDriverPackageId("fit.tsi.link-webhook");
        c.setDriverVersion("1.0.0");
        c.setStep(1, "VALID", Docs.of("modelCode", "MD-105", "lifecycle", m.lifecycle(), "posture", "ACCEPTABLE"), null, "deviceadmin1", now);
        c.setStep(2, "VALID", Docs.of("option", "P6", "driver", Docs.of("packageId", "fit.tsi.link-webhook", "version", "1.0.0", "status", "CERTIFIED")),
                null, "deviceadmin1", now);
        List<Map<String, Object>> items = WizardRules.prerequisites(packs.prerequisites("P6"), m.family(), m.highConfidence(), "P6");
        items.forEach(i -> {
            if (!"NOT_APPLICABLE".equals(i.get("status"))) {
                i.put("status", "DONE");
                i.put("evidence", Docs.of("reference", "DEMO-EVIDENCE", "text", "Synthetic demo evidence"));
            }
        });
        c.setStep(3, "VALID", Docs.of("items", items), null, "deviceadmin1", now);
        c.setCurrentStep(4);
        c.setPaused(true);
        store.insert(c);
    }

    private void qcHistory() {
        Instant now = support.now();
        LocalDate today = support.today();
        check("DEV-000001", "SPIRO_SYRINGE_3L", now.minus(Duration.ofDays(2)), null, null); // expired → locked out (E2E step 10)
        check("DEV-000001", "SPIRO_LINEARITY", now.minus(Duration.ofDays(2)), null, null);
        check("DEV-000002", "AUDIO_DAILY_BIOLOGICAL", now.minus(Duration.ofHours(1)), null, null);
        check("DEV-000003", "BP_VALIDATION_INTERVAL", now.minus(Duration.ofDays(30)), null, null);
        check("DEV-000004", "BP_VALIDATION_INTERVAL", now.minus(Duration.ofDays(30)), null, null);
        check("DEV-000005", "BREATH_ACCURACY_CHECK", now.minus(Duration.ofDays(5)), null, null);
        check("DEV-000006", "POC_QC_LOT", now.minus(Duration.ofDays(2)), "AF-LOT-2026-10", today.plusDays(60));
        check("DEV-000008", "BP_VALIDATION_INTERVAL", now.minus(Duration.ofDays(60)), null, null);
    }

    private void check(String instance, String type, Instant at, String lot, LocalDate expiry) {
        support.jdbc().update("""
                insert into devicecat.qc_check (id, device_instance_id, check_type, performed_at, outcome, lot, lot_expiry, source, recorded_by, recorded_at)
                values (?, ?, ?, ?, 'PASS', ?, ?, 'DEMO', 'nurse1', ?)""", UUID.randomUUID(), instance, type, Timestamp.from(at), lot,
                expiry == null ? null : java.sql.Date.valueOf(expiry), Timestamp.from(at));
    }
}
