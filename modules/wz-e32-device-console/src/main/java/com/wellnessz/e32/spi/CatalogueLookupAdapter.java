package com.wellnessz.e32.spi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.config.WizardRules;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/**
 * {@link DeviceCatalogueLookup} for the engine (E31), capture (E33) and security (E34): only <b>live</b> configuration
 * versions are visible (PUBLISHED, plus SUSPENDED / QUARANTINED with that status so the engine can refuse with
 * {@code device_quarantined}); drafts and decommissioned instances never are. Parameters carry secret references only.
 */
@Component("e32CatalogueLookupAdapter")
public class CatalogueLookupAdapter implements DeviceCatalogueLookup {

    private final ConfigurationStore configurations;
    private final ModelStore models;
    private final DriverStore drivers;
    private final CalibrationService calibration;
    private final E32Support support;

    public CatalogueLookupAdapter(ConfigurationStore configurations, ModelStore models, DriverStore drivers, CalibrationService calibration,
                                  E32Support support) {
        this.configurations = configurations;
        this.models = models;
        this.drivers = drivers;
        this.calibration = calibration;
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceInstance> instance(String deviceInstanceId) {
        if (deviceInstanceId == null || !support.enabled()) {
            return Optional.empty();
        }
        return configurations.live(deviceInstanceId).map(this::toInstance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceInstance> instancesForEndpoint(String endpointId, String deviceSerial) {
        if (endpointId == null || !support.enabled()) {
            return List.of();
        }
        List<DeviceInstance> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Configuration c : configurations.liveForEndpoint(endpointId)) {
            if (!seen.add(c.deviceInstanceId())) {
                continue;
            }
            if (deviceSerial == null || deviceSerial.equalsIgnoreCase(c.serial()) || dmsDevice(c, deviceSerial)) {
                out.add(toInstance(c));
            }
        }
        return out;
    }

    private static boolean dmsDevice(Configuration c, String serial) {
        return Docs.strings(Docs.at(c.parameters(), "dms.deviceIds")).stream().anyMatch(serial::equalsIgnoreCase);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceInstance> instancesAtStation(String siteCode, String stationCode, DeviceCodes.Category category) {
        if (siteCode == null || stationCode == null || !support.enabled()) {
            return List.of();
        }
        List<DeviceInstance> out = new ArrayList<>();
        for (Configuration c : configurations.liveAtStation(siteCode, stationCode)) {
            DeviceInstance i = toInstance(c);
            if (category == null || i.categories().contains(category)) {
                out.add(i);
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DriverPackage> driverPackage(String packageId, String version) {
        if (packageId == null || version == null) {
            return Optional.empty();
        }
        return drivers.find(packageId, version).filter(d -> !"REVOKED".equals(d.status()))
                .map(d -> new DriverPackage(d.packageId(), d.version(), family(d.family()), d.manifestJson(), d.sha256(), d.signatureKeyId(),
                        d.signatureValue()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FormBinding> binding(String deviceInstanceId, String formCode) {
        if (deviceInstanceId == null || formCode == null) {
            return Optional.empty();
        }
        Optional<Configuration> live = configurations.live(deviceInstanceId);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        Configuration c = live.get();
        Map<String, Object> manifest = drivers.find(c.driverPackageId(), c.driverVersion()).map(d -> support.readMap(d.manifestJson())).orElse(Map.of());
        List<String> gates = WizardRules.gates(manifest, Docs.strings(c.common().get("qualityGateAdditions")));
        for (Object o : c.bindings()) {
            Map<String, Object> b = Docs.map(o);
            if (formCode.equals(Docs.str(b.get("formCode")))) {
                return Optional.of(toBinding(b, gates, c.verifyBeforePost()));
            }
        }
        for (Object o : Docs.list(manifest.get("bindings"))) {
            Map<String, Object> b = Docs.map(o);
            if (formCode.equals(Docs.str(b.get("formCode")))) {
                return Optional.of(toBinding(b, gates, c.verifyBeforePost()));
            }
        }
        return Optional.empty();
    }

    private static FormBinding toBinding(Map<String, Object> b, List<String> gates, boolean instanceVerify) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        Docs.map(b.get("fields")).forEach((k, v) -> fields.put(k, Docs.str(v)));
        DeviceCodes.Category cat = category(Docs.str(b.get("category")));
        boolean verify = instanceVerify || Docs.bool(b.get("verifyBeforePost"), true);
        return new FormBinding(Docs.str(b.get("formCode")), Docs.str(b.get("formVersion")) == null ? "1" : Docs.str(b.get("formVersion")), cat,
                Docs.str(b.get("target")), fields, verify, gates);
    }

    @Override
    @Transactional(readOnly = true)
    public QcStatus qcStatus(String deviceInstanceId, LocalDate on, String lot) {
        if (deviceInstanceId == null) {
            return QcStatus.unknown();
        }
        return calibration.qcStatus(deviceInstanceId, on, lot);
    }

    DeviceInstance toInstance(Configuration c) {
        ModelStore.ModelRow m = models.find(c.modelCode()).orElse(null);
        Set<DeviceCodes.Category> categories = new LinkedHashSet<>();
        for (Object o : c.bindings()) {
            DeviceCodes.Category cat = category(Docs.str(Docs.map(o).get("category")));
            if (cat != null) {
                categories.add(cat);
            }
        }
        DeviceCodes.Option option = c.option() == null ? null : DeviceCodes.Option.ofCode(c.option());
        String tz = Docs.str(c.common().get("timeZone"));
        return new DeviceInstance(c.deviceInstanceId(), c.modelCode(), m == null ? null : m.family(), m == null ? null : m.manufacturer(),
                m == null ? null : m.model(), c.serial(), c.udi(), c.firmware(), c.equipmentDeviceId(), option, c.status(), c.siteCode(), c.stationCode(),
                c.endpointId(), c.driverPackageId(), c.driverVersion(), categories, Docs.copyMap(c.parameters()), c.associationMethods(),
                c.verifyBeforePost(), c.formCodes(), tz == null ? "Europe/London" : tz, c.tenantCode(), c.version());
    }

    private static DeviceCodes.Family family(String name) {
        for (DeviceCodes.Family f : DeviceCodes.Family.values()) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public static DeviceCodes.Category category(String name) {
        for (DeviceCodes.Category c : DeviceCodes.Category.values()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }
}
