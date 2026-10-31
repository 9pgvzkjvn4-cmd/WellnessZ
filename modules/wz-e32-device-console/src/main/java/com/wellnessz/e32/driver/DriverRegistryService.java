package com.wellnessz.e32.driver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Driver-package registry (E32-F01 "certified drivers", E31-HLR-03): immutable, signed package versions verified through
 * {@link DevicePackageSignatures} (E34), certification by a second person, deprecation and revocation (revocation suspends
 * every live instance on that version and raises a connector alert). Compatibility matrix models × options × versions.
 */
@Service("e32DriverRegistryService")
public class DriverRegistryService {

    private final DriverStore drivers;
    private final ModelStore models;
    private final ConfigurationStore configurations;
    private final E32Support support;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public DriverRegistryService(DriverStore drivers, ModelStore models, ConfigurationStore configurations, E32Support support,
                                 org.springframework.transaction.support.TransactionTemplate tx) {
        this.drivers = drivers;
        this.models = models;
        this.configurations = configurations;
        this.support = support;
        this.tx = tx;
    }

    public record RegisterCommand(Map<String, Object> packageJson, String conformanceRunId, String conformanceEvidenceSha256) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String modelCode, String status) {
        return drivers.all().stream()
                .filter(d -> modelCode == null || d.models().contains(modelCode))
                .filter(d -> status == null || status.equals(d.status()))
                .map(DriverRegistryService::entry).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String packageId, String version) {
        DriverStore.DriverRow d = drivers.require(packageId, version);
        Map<String, Object> e = entry(d);
        e.put("manifest", drivers.manifest(d));
        return e;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> matrix(String modelCode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DriverStore.DriverRow d : drivers.all()) {
            for (String m : d.models()) {
                if (modelCode != null && !modelCode.equals(m)) {
                    continue;
                }
                for (String o : d.options()) {
                    rows.add(Docs.of("modelCode", m, "option", o, "packageId", d.packageId(), "version", d.version(), "adapter", d.adapter(),
                            "status", d.status(), "engineRange", d.engineRange()));
                }
            }
        }
        return rows;
    }

    public static Map<String, Object> entry(DriverStore.DriverRow d) {
        Map<String, Object> m = new LinkedHashMap<>(Docs.of("packageId", d.packageId(), "version", d.version(), "family", d.family(),
                "models", d.models(), "adapter", d.adapter(), "options", d.options(), "status", d.status(), "sha256", d.sha256(),
                "signatureKeyId", d.signatureKeyId(), "conformanceRunId", d.conformanceRunId(),
                "conformanceEvidenceSha256", d.conformanceEvidenceSha256(), "engineRange", d.engineRange(), "registeredBy", d.registeredBy(),
                "registeredAt", d.registeredAt(), "certifiedBy", d.certifiedBy(), "certifiedAt", d.certifiedAt()));
        if (d.statusReason() != null) {
            m.put("statusReason", d.statusReason());
        }
        return m;
    }

    /** Validation report without persistence (schema, coverage, secret scan). */
    @Transactional(readOnly = true)
    public DriverPackageRules.Report validate(Map<String, Object> pkg) {
        Map<String, List<String>> modelOptions = new LinkedHashMap<>();
        for (String code : Docs.strings(pkg.get("models"))) {
            models.find(code).ifPresent(m -> modelOptions.put(code, m.options()));
        }
        return DriverPackageRules.validate(pkg, modelOptions);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public Map<String, Object> register(CurrentUser user, RegisterCommand cmd) {
        support.requireEnabled();
        Map<String, Object> pkg = cmd.packageJson() == null ? Map.of() : cmd.packageJson();
        if (cmd.conformanceRunId() == null || cmd.conformanceRunId().isBlank()) {
            throw ApiException.badRequest("validation_failed", "conformanceRunId is required (passing conformance run in the simulator)");
        }
        DriverPackageRules.Report report = validate(pkg);
        if (!report.valid()) {
            throw new IssuesException("package_invalid", "The driver package is not valid", report.errors());
        }
        Map<String, Object> sig = Docs.map(pkg.get("signature"));
        if (Docs.str(sig.get("value")) == null || Docs.str(sig.get("keyId")) == null) {
            throw E32Support.unprocessable("signature_invalid", "The package must be signed (signature.keyId and signature.value)");
        }
        DevicePackageSignatures.Verification v = support.spis().signatures().verify(report.canonical().getBytes(StandardCharsets.UTF_8),
                Docs.str(sig.get("value")), Docs.str(sig.get("keyId")));
        if (v == null || !v.valid()) {
            support.securityEvent("PACKAGE_SIGNATURE_INVALID", DeviceCodes.Severity.HIGH, null, null, null, user.username(),
                    v == null ? "signature_invalid" : v.failureCode(), Map.of("keyId", Docs.str(sig.get("keyId"))));
            support.auditDenied("DEVICE_DRIVER_REGISTER", "DEVICE_DRIVER", Docs.str(pkg.get("packageId")) + "@" + Docs.str(pkg.get("version")),
                    "signature_invalid");
            throw E32Support.unprocessable("signature_invalid", "The package signature could not be verified");
        }
        return store(user.username(), pkg, report, sig, "REGISTERED", cmd.conformanceRunId(), cmd.conformanceEvidenceSha256());
    }

    /**
     * Registers a package shipped with the product (demo seeder): the signature is created through E34 when available,
     * otherwise the package is registered unsigned (local / test only). Signing happens outside the registry transaction.
     */
    public Optional<Map<String, Object>> registerBundled(String actor, Map<String, Object> pkg, String status) {
        DriverPackageRules.Report report = validate(pkg);
        if (!report.valid() || drivers.exists(Docs.str(pkg.get("packageId")), Docs.str(pkg.get("version")))) {
            return Optional.empty();
        }
        Map<String, Object> sig = new LinkedHashMap<>();
        try {
            DevicePackageSignatures.Signature s = support.spis().signatures().sign(actor, report.canonical().getBytes(StandardCharsets.UTF_8));
            if (s != null) {
                sig = Docs.of("keyId", s.keyId(), "algorithm", s.algorithm(), "value", s.signatureBase64());
            }
        } catch (RuntimeException unavailable) {
            // E34 not deployed (or the signing key is not provisioned): bundled demo package stays unsigned
        }
        Map<String, Object> signature = sig;
        return Optional.ofNullable(tx.execute(s -> store(actor, pkg, report, signature, status, "BUNDLED-" + report.sha256().substring(0, 12),
                report.sha256())));
    }

    private Map<String, Object> store(String actor, Map<String, Object> pkg, DriverPackageRules.Report report, Map<String, Object> sig, String status,
                                      String runId, String evidence) {
        String packageId = Docs.str(pkg.get("packageId"));
        String version = Docs.str(pkg.get("version"));
        if (drivers.exists(packageId, version)) {
            throw ApiException.conflict("package_version_exists", "This package version is already registered (versions are immutable)");
        }
        drivers.insert(pkg, report.canonical(), report.sha256(), sig, status, runId, evidence, actor);
        support.history("DRIVER", packageId + "@" + version, null, actor, "REGISTER", null,
                Docs.of("status", status, "sha256", report.sha256(), "conformanceRunId", runId), null);
        support.audit("DEVICE_DRIVER_REGISTER", "DEVICE_DRIVER", packageId + "@" + version);
        Map<String, Object> out = entry(drivers.require(packageId, version));
        out.put("warnings", report.warnings());
        return out;
    }

    @Transactional
    public Map<String, Object> certify(CurrentUser user, String packageId, String version) {
        support.requireEnabled();
        DriverStore.DriverRow d = drivers.require(packageId, version);
        if (user.username().equals(d.registeredBy())) {
            throw ApiException.conflict("four_eyes_violation", "The person who registered a driver package cannot certify it");
        }
        if (!"REGISTERED".equals(d.status())) {
            throw E32Support.unprocessable("invalid_status", "Only a REGISTERED package can be certified");
        }
        drivers.certify(d, user.username());
        support.history("DRIVER", d.ref(), null, user.username(), "CERTIFY", null, Docs.of("from", d.status(), "to", "CERTIFIED"), null);
        support.audit("DEVICE_DRIVER_CERTIFY", "DEVICE_DRIVER", d.ref());
        return entry(drivers.require(packageId, version));
    }

    @Transactional
    public Map<String, Object> deprecate(CurrentUser user, String packageId, String version, String reason) {
        support.requireEnabled();
        DriverStore.DriverRow d = drivers.require(packageId, version);
        if ("REVOKED".equals(d.status())) {
            throw E32Support.unprocessable("invalid_status", "A revoked package cannot be deprecated");
        }
        drivers.setStatus(d, "DEPRECATED", reason, user.username());
        support.history("DRIVER", d.ref(), null, user.username(), "DEPRECATE", null, Docs.of("from", d.status(), "to", "DEPRECATED"), null);
        support.audit("DEVICE_DRIVER_CERTIFY", "DEVICE_DRIVER", d.ref(), "deprecated");
        return entry(drivers.require(packageId, version));
    }

    /** SECURITY_OPS: revocation suspends every live instance pinned to the version and alerts the on-duty administrator. */
    @Transactional
    public Map<String, Object> revoke(CurrentUser user, String packageId, String version, String reason) {
        support.requireEnabled();
        DriverStore.DriverRow d = drivers.require(packageId, version);
        drivers.setStatus(d, "REVOKED", reason, user.username());
        List<String> suspended = new ArrayList<>();
        for (Configuration c : configurations.liveOnDriver(packageId, version)) {
            if (Configuration.PUBLISHED.equals(c.status())) {
                c.setStatus(Configuration.SUSPENDED);
                c.setStatusReason("DRIVER_REVOKED");
                configurations.update(c, user.username());
                support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "SUSPEND", null,
                        Docs.of("reason", "DRIVER_REVOKED", "driver", d.ref()), null);
            }
            suspended.add(c.deviceInstanceId());
            support.notifyOnDuty(c.siteCode(), "device.connector-alert", alertParams(c), true);
            support.jdbc().update("""
                    insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                    values (?, ?, ?, 'DRIVER_REVOKED', 'HIGH', ?, ?, 'OPEN', ?) on conflict do nothing""",
                    java.util.UUID.randomUUID(), c.deviceInstanceId(), c.siteCode(), "Driver " + d.ref() + " revoked",
                    "DRIVER_REVOKED:" + c.deviceInstanceId() + ":" + d.ref(), java.sql.Timestamp.from(support.now()));
        }
        support.history("DRIVER", d.ref(), null, user.username(), "REVOKE", null, Docs.of("from", d.status(), "to", "REVOKED", "suspended", suspended), null);
        support.audit("DEVICE_DRIVER_REVOKE", "DEVICE_DRIVER", d.ref());
        support.securityEvent("PACKAGE_REVOKED", DeviceCodes.Severity.HIGH, null, null, null, user.username(), "package_revoked",
                Map.of("keyId", d.signatureKeyId() == null ? "-" : d.signatureKeyId()));
        Map<String, Object> out = entry(drivers.require(packageId, version));
        out.put("suspendedInstances", suspended);
        return out;
    }

    private Map<String, String> alertParams(Configuration c) {
        Map<String, String> p = support.params(c.deviceInstanceId(), c.siteCode());
        p.put("level", "HIGH");
        return p;
    }

    /** 422 with a list of field issues ({@code errors[{field, code, message}]}). */
    public static class IssuesException extends ApiException {

        private static final long serialVersionUID = 1L;
        private final transient List<DriverPackageRules.Issue> issues;

        public IssuesException(String code, String message, List<DriverPackageRules.Issue> issues) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
            this.issues = List.copyOf(issues);
        }

        @Override
        public Map<String, Object> properties() {
            List<Map<String, Object>> errors = new ArrayList<>();
            if (issues != null) {
                for (DriverPackageRules.Issue i : issues) {
                    errors.add(Docs.of("field", i.field(), "code", i.code(), "message", i.message()));
                }
            }
            return Map.of("errors", errors);
        }
    }
}
