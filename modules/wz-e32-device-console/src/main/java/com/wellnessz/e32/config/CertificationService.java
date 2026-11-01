package com.wellnessz.e32.config;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.option.OptionRules;
import com.wellnessz.e32.support.CanonicalJson;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/**
 * Certification record and PDF (E32-HLR-10, E32-F10, contract §13.5): immutable record with the configuration snapshot
 * (secrets as references), its SHA-256, the driver, test evidence, nurse review, prerequisites, posture exception,
 * submitter and approver(s) and the data-flow summary. Rendered with the platform {@link PdfRenderer} and stored with its
 * SHA-256 and an approval-signature hash. The employee-bound DocumentService is not used – the record concerns a device.
 */
@Service("e32CertificationService")
public class CertificationService {

    private final E32Support support;
    private final ModelStore models;
    private final DriverStore drivers;
    private final PdfRenderer renderer;

    public CertificationService(E32Support support, ModelStore models, DriverStore drivers, PdfRenderer renderer) {
        this.support = support;
        this.models = models;
        this.drivers = drivers;
        this.renderer = renderer;
    }

    /** Snapshot of a configuration version (no secret values – only references). */
    public Map<String, Object> snapshot(Configuration c) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("configurationId", c.configurationId());
        s.put("version", c.version());
        s.put("deviceInstanceId", c.deviceInstanceId());
        s.put("modelCode", c.modelCode());
        s.put("option", c.option());
        s.put("siteCode", c.siteCode());
        s.put("roomCode", c.roomCode());
        s.put("stationCode", c.stationCode());
        s.put("tenantCode", c.tenantCode());
        s.put("serial", c.serial());
        s.put("udi", c.udi());
        s.put("firmware", c.firmware());
        s.put("softwareVersion", c.softwareVersion());
        s.put("equipmentDeviceId", c.equipmentDeviceId());
        s.put("driver", Docs.of("packageId", c.driverPackageId(), "version", c.driverVersion()));
        s.put("parameters", Docs.copy(c.parameters()));
        s.put("common", Docs.copy(c.common()));
        s.put("secretReferences", Docs.copy(c.secrets()));
        s.put("associationMethods", c.associationMethods());
        s.put("services", Docs.strings(c.stepData(5).get("services")));
        s.put("formCodes", c.formCodes());
        s.put("bindings", Docs.copy(c.bindings()));
        s.values().removeIf(java.util.Objects::isNull);
        return s;
    }

    /**
     * The endpoint hop of the data flow: its zone as E34 holds it (provisioning SPI) – the step-3 zone is the <i>device's</i>
     * segment behind the endpoint and is shown as {@code deviceZone}. Before publication (or without E34) the endpoint zone is
     * the E34 policy default for the kind, labelled as such.
     */
    Map<String, Object> endpoint(Configuration c) {
        String deviceZone = c.zone() == null ? "DEVICE_VLAN" : c.zone();
        String kind = c.endpointKind();
        String zone = null;
        String zoneSource = "E34_POLICY_DEFAULT";
        String status = null;
        String host = c.endpointId() != null ? c.endpointId() : c.option() == null ? null : OptionRules.hostEndpoint(c.option(), c.parameters());
        if (host != null) {
            try {
                DeviceEndpointProvisioning.EndpointState st = support.spis().provisioning().state(host);
                if (st != null) {
                    zone = st.zone();
                    zoneSource = zone == null ? zoneSource : "E34";
                    status = st.status() == null ? null : st.status().name();
                    kind = st.kind() != null ? st.kind().name() : kind;
                }
            } catch (RuntimeException unavailable) {
                // E34 not deployed or endpoint unknown: fall back to the policy default below
            }
        }
        if (zone == null) {
            zone = defaultZone(kind);
            zoneSource = "E34_POLICY_DEFAULT";
        }
        Map<String, Object> ep = Docs.of("endpointId", host, "kind", kind, "zone", zone, "zoneSource", zoneSource, "status", status,
                "deviceZone", deviceZone);
        ep.values().removeIf(java.util.Objects::isNull);
        return ep;
    }

    /** Contract §15.4 default zone of an endpoint kind (mirrors the E34 policy; shown only until E34 reports the actual zone). */
    static String defaultZone(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "GATEWAY", "DEVICE_HOST_AGENT", "DATA_MANAGER" -> "GATEWAY_ZONE";
            case "MOBILE_APP", "KIOSK" -> "CLINIC_LAN";
            case "VENDOR_CLOUD" -> "VENDOR_CLOUD";
            case "DEVICE" -> "DEVICE_VLAN";
            default -> null;
        };
    }

    public Map<String, Object> dataFlow(Configuration c, List<String> gates) {
        Map<String, Object> flow = new LinkedHashMap<>();
        var model = models.find(c.modelCode());
        flow.put("device", Docs.of("deviceInstanceId", c.deviceInstanceId(), "modelCode", c.modelCode(),
                "model", model.map(m -> m.manufacturer() + " " + m.model()).orElse(null), "siteCode", c.siteCode(), "stationCode", c.stationCode()));
        flow.put("endpoint", endpoint(c));
        var driver = c.driverPackageId() == null ? null : drivers.find(c.driverPackageId(), c.driverVersion()).orElse(null);
        flow.put("adapter", driver == null ? null : driver.adapter());
        flow.put("option", c.option());
        flow.put("driver", driver == null ? null : Docs.of("packageId", driver.packageId(), "version", driver.version(), "sha256", driver.sha256(),
                "status", driver.status()));
        flow.put("gates", gates);
        flow.put("associationMethods", c.associationMethods());
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Object o : c.bindings()) {
            Map<String, Object> b = Docs.map(o);
            String target = Docs.str(b.get("target"));
            bindings.add(Docs.of("form", b.get("formCode"), "formVersion", b.get("formVersion"), "target", target,
                    "targetModule", target == null ? null : target.substring(0, 3), "category", b.get("category"),
                    "fields", new ArrayList<>(Docs.map(b.get("fields")).values()), "verifyBeforePost", Docs.bool(b.get("verifyBeforePost"), true)));
        }
        flow.put("bindings", bindings);
        flow.put("retention", Docs.of("rawPayload", Docs.str(c.common().get("retentionClass")) == null ? "DEVICE_RAW_PAYLOAD"
                : Docs.str(c.common().get("retentionClass")), "configuration", "DEVICE_CONFIGURATION", "calibrationQc", "DEVICE_CALIBRATION_QC"));
        flow.values().removeIf(java.util.Objects::isNull);
        return flow;
    }

    /** Creates the immutable certification record and PDF for the approved version. */
    public Map<String, Object> certify(Configuration c, List<String> gates, String approvalRef) {
        String id = support.nextCertificationId();
        Map<String, Object> snapshot = snapshot(c);
        String snapshotSha = Docs.sha256(CanonicalJson.write(snapshot));
        var model = models.require(c.modelCode());
        var driver = drivers.require(c.driverPackageId(), c.driverVersion());
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Object o : c.tests()) {
            Map<String, Object> t = Docs.map(o);
            tests.add(Docs.of("testRunId", t.get("testRunId"), "mode", t.get("mode"), "status", t.get("status"), "evidenceSha256", t.get("evidenceSha256"),
                    "finishedAt", t.get("finishedAt")));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("certificationId", id);
        record.put("configurationId", c.configurationId());
        record.put("version", c.version());
        record.put("deviceInstanceId", c.deviceInstanceId());
        record.put("model", Docs.of("modelCode", model.modelCode(), "manufacturer", model.manufacturer(), "model", model.model(),
                "family", model.family().name(), "posture", model.posture()));
        record.put("option", c.option());
        record.put("site", c.siteCode());
        record.put("station", c.stationCode());
        record.put("configurationSnapshot", snapshot);
        record.put("snapshotSha256", snapshotSha);
        record.put("driver", Docs.of("packageId", driver.packageId(), "version", driver.version(), "sha256", driver.sha256(),
                "signatureKeyId", driver.signatureKeyId()));
        record.put("tests", tests);
        record.put("nurseReview", Docs.copy(c.data().get("nurseReview")));
        record.put("prerequisites", Docs.copy(c.stepData(3).get("items")));
        record.put("postureException", Docs.copy(c.data().get("postureException")));
        record.put("simulatorOnlyJustification", c.data().get("simulatorOnlyJustification"));
        record.put("submittedBy", c.submittedBy());
        record.put("submittedAt", c.submittedAt() == null ? null : c.submittedAt().toString());
        record.put("approvedBy", c.approvedBy());
        record.put("coApprovedBy", c.coApprovedBy());
        record.put("approvedAt", c.approvedAt() == null ? null : c.approvedAt().toString());
        record.put("approvalRef", approvalRef);
        record.put("dataFlow", dataFlow(c, gates));
        record.values().removeIf(java.util.Objects::isNull);
        String approvalSignature = Docs.sha256(snapshotSha + "|" + c.submittedBy() + "|" + c.approvedBy() + "|" + c.coApprovedBy() + "|"
                + record.get("approvedAt") + "|" + id);
        record.put("approvalSignatureHash", approvalSignature);
        byte[] pdf = render(record);
        String pdfSha = Docs.sha256(pdf);
        record.put("pdfSha256", pdfSha);
        support.jdbc().update("""
                insert into devicecat.certification (certification_id, configuration_id, version, device_instance_id, record, snapshot_sha256, pdf,
                    pdf_sha256, approval_signature_hash, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, c.configurationId(), c.version(), c.deviceInstanceId(), support.write(record), snapshotSha, pdf, pdfSha, approvalSignature,
                Timestamp.from(support.now()));
        return record;
    }

    private byte[] render(Map<String, Object> r) {
        List<PdfRenderer.Section> sections = new ArrayList<>();
        Map<String, Object> model = Docs.map(r.get("model"));
        sections.add(new PdfRenderer.Section("Device", "Instance " + r.get("deviceInstanceId") + " - " + model.get("manufacturer") + " "
                + model.get("model") + " (" + model.get("modelCode") + ", " + model.get("family") + ")\nSite " + r.get("site") + ", station "
                + r.get("station") + "\nConnectivity option " + r.get("option") + "\nConfiguration " + r.get("configurationId") + " version "
                + r.get("version")));
        Map<String, Object> driver = Docs.map(r.get("driver"));
        sections.add(new PdfRenderer.Section("Driver package", driver.get("packageId") + "@" + driver.get("version") + "\nSHA-256 "
                + driver.get("sha256") + "\nSignature key " + (driver.get("signatureKeyId") == null ? "-" : driver.get("signatureKeyId"))));
        StringBuilder prereq = new StringBuilder();
        for (Object o : Docs.list(r.get("prerequisites"))) {
            Map<String, Object> p = Docs.map(o);
            prereq.append(p.get("code")).append(": ").append(p.get("status")).append('\n');
        }
        sections.add(new PdfRenderer.Section("Prerequisites", prereq.isEmpty() ? "-" : prereq.toString()));
        StringBuilder tests = new StringBuilder();
        for (Object o : Docs.list(r.get("tests"))) {
            Map<String, Object> t = Docs.map(o);
            tests.append(t.get("mode")).append(' ').append(t.get("status")).append(" - run ").append(t.get("testRunId")).append(" - evidence ")
                    .append(t.get("evidenceSha256")).append('\n');
        }
        Map<String, Object> review = Docs.map(r.get("nurseReview"));
        tests.append("Nurse review: ").append(review.isEmpty() ? "-" : review.get("outcome") + " by " + review.get("by") + " at " + review.get("at"));
        if (r.get("simulatorOnlyJustification") != null) {
            tests.append("\nSimulator-only justification recorded (approved by device approver and security operations)");
        }
        sections.add(new PdfRenderer.Section("Test evidence", tests.toString()));
        Map<String, Object> flow = Docs.map(r.get("dataFlow"));
        StringBuilder f = new StringBuilder();
        Map<String, Object> ep = Docs.map(flow.get("endpoint"));
        f.append("Endpoint ").append(ep.get("endpointId") == null ? "(assigned at publication)" : ep.get("endpointId")).append(" - zone ")
                .append(ep.get("zone")).append(ep.get("deviceZone") == null ? "" : " (device in " + ep.get("deviceZone") + ")").append('\n');
        f.append("Quality gates: ").append(Docs.strings(flow.get("gates"))).append('\n');
        f.append("Association: ").append(Docs.strings(flow.get("associationMethods"))).append('\n');
        for (Object o : Docs.list(flow.get("bindings"))) {
            Map<String, Object> b = Docs.map(o);
            f.append("Form ").append(b.get("form")).append(" -> ").append(b.get("target")).append(" fields ").append(Docs.strings(b.get("fields")))
                    .append(Docs.bool(b.get("verifyBeforePost"), true) ? " (verified before posting)" : " (posted automatically)").append('\n');
        }
        sections.add(new PdfRenderer.Section("Data flow", f.toString()));
        String snapshot = CanonicalJson.write(r.get("configurationSnapshot"));
        sections.add(new PdfRenderer.Section("Configuration snapshot (secrets as references only)",
                "SHA-256 " + r.get("snapshotSha256") + "\n" + wrap(snapshot)));
        sections.add(new PdfRenderer.Section("Approval", "Submitted by " + r.get("submittedBy") + " at " + r.get("submittedAt") + "\nApproved by "
                + r.get("approvedBy") + (r.get("coApprovedBy") == null ? "" : " and " + r.get("coApprovedBy")) + " at " + r.get("approvedAt")
                + "\nApproval signature hash " + r.get("approvalSignatureHash")));
        return renderer.render("Device certification record", Docs.str(r.get("certificationId")), Docs.integer(r.get("version")), sections,
                "Four-eyes certification of a device configuration - no person data is contained in this record.").pdf();
    }

    private static String wrap(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i += 100) {
            out.append(s, i, Math.min(s.length(), i + 100)).append('\n');
        }
        return out.toString();
    }

    public Map<String, Object> record(String configurationId) {
        List<String> rows = support.jdbc().queryForList(
                "select record from devicecat.certification where configuration_id = ? order by version desc limit 1", String.class, configurationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Certification record");
        }
        return support.readMap(rows.get(0));
    }

    public byte[] pdf(String configurationId) {
        List<byte[]> rows = support.jdbc().query("select pdf from devicecat.certification where configuration_id = ? order by version desc limit 1",
                (rs, i) -> rs.getBytes("pdf"), configurationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Certification record");
        }
        return rows.get(0);
    }

    public boolean exists(String configurationId, int version) {
        return !support.jdbc().queryForList("select 1 from devicecat.certification where configuration_id = ? and version = ?", Integer.class,
                configurationId, version).isEmpty();
    }
}
