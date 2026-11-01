package com.wellnessz.e31.driver;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.EngineSettings;

import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the driver package pinned by a device instance ({@code packageId@version}) from the E32 registry
 * ({@link DeviceCatalogueLookup#driverPackage}) and verifies its signature through {@link DevicePackageSignatures} (E34)
 * before first use – fail closed: without a valid signature (or without E34) no message is parsed. Verified packages are
 * cached by SHA-256. Virtual simulator instances use the bundled sample packages.
 */
@Component("e31DriverRuntime")
public class DriverRuntime {

    public static final String VIRTUAL_PREFIX = "SIM-";

    private final DeviceSpis spis;
    private final BundledDrivers bundled;
    private final DriverParser parser;
    private final Clock clock;
    private final Map<String, DriverPackage> verified = new ConcurrentHashMap<>();

    public DriverRuntime(DeviceSpis spis, BundledDrivers bundled, JsonMapper json, Clock clock) {
        this.spis = spis;
        this.bundled = bundled;
        this.parser = new DriverParser(json);
        this.clock = clock;
    }

    public static boolean virtual(String deviceInstanceId) {
        return deviceInstanceId != null && deviceInstanceId.startsWith(VIRTUAL_PREFIX);
    }

    public DriverPackage forInstance(DeviceCatalogueLookup.DeviceInstance instance, String pinnedVersion) {
        String version = pinnedVersion != null ? pinnedVersion : instance.driverVersion();
        if (virtual(instance.deviceInstanceId())) {
            return resolve(instance.driverPackageId(), version, instance);
        }
        if (instance.driverPackageId() == null) {
            throw new EngineException("no_driver_for_option", "The device instance has no driver package", true);
        }
        DeviceCatalogueLookup.DriverPackage published = spis.catalogue().driverPackage(instance.driverPackageId(), version)
                .orElseThrow(() -> new EngineException("no_driver_for_option", "Driver package " + instance.driverPackageId() + "@" + version
                        + " is not in the registry", true));
        DriverPackage cached = verified.get(published.sha256());
        if (cached != null) {
            return cached;
        }
        DriverPackage pkg = parser.parse(published.manifestJson());
        verify(published, pkg, instance);
        if (pkg.engineCompatibility() != null && !SemVer.satisfies(EngineSettings.ENGINE_VERSION, pkg.engineCompatibility())) {
            throw new EngineException("no_driver_for_option", "Driver package is not compatible with engine " + EngineSettings.ENGINE_VERSION, true);
        }
        verified.put(published.sha256(), pkg);
        return pkg;
    }

    /**
     * A package for simulator / test runs: a bundled sample, else the signed registry version (verified like production).
     */
    public DriverPackage resolve(String packageId, String version, DeviceCatalogueLookup.DeviceInstance context) {
        java.util.Optional<DriverPackage> sample = bundled.get(packageId, version);
        if (sample.isPresent()) {
            return sample.get();
        }
        if (version == null) {
            throw new EngineException("no_driver_for_option", "Driver package version required", true);
        }
        DeviceCatalogueLookup.DriverPackage published = spis.catalogue().driverPackage(packageId, version)
                .orElseThrow(() -> new EngineException("no_driver_for_option", "Driver package not found", true));
        DriverPackage cached = verified.get(published.sha256());
        if (cached != null) {
            return cached;
        }
        DriverPackage pkg = parser.parse(published.manifestJson());
        verify(published, pkg, context);
        verified.put(published.sha256(), pkg);
        return pkg;
    }

    void verify(DeviceCatalogueLookup.DriverPackage published, DriverPackage pkg, DeviceCatalogueLookup.DeviceInstance instance) {
        String signature = published.signatureBase64() != null ? published.signatureBase64() : pkg.signature() == null ? null : pkg.signature().value();
        String keyId = published.signatureKeyId() != null ? published.signatureKeyId() : pkg.signature() == null ? null : pkg.signature().keyId();
        DevicePackageSignatures signatures = spis.signatures(); // 503 module_disabled without E34: retried, never parsed unsigned
        boolean valid = false;
        if (signature != null && keyId != null) {
            DevicePackageSignatures.Verification v = signatures.verify(published.manifestJson().getBytes(StandardCharsets.UTF_8), signature, keyId);
            valid = v.valid();
            if (!valid && !published.manifestJson().equals(pkg.canonicalJson())) {
                valid = signatures.verify(pkg.canonicalJson().getBytes(StandardCharsets.UTF_8), signature, keyId).valid();
            }
        }
        if (!valid) {
            try {
                spis.securityEvents().publish(new DeviceSecurityEventPublisher.DeviceSecurityEvent("PACKAGE_SIGNATURE_INVALID", DeviceCodes.Severity.HIGH,
                        instance == null ? null : instance.endpointId(), instance == null ? null : instance.deviceInstanceId(),
                        instance == null ? null : instance.siteCode(), "device-engine", "E31", "signature_invalid",
                        Map.of("packageId", pkg.packageId(), "version", pkg.version()), clock.instant()));
            } catch (RuntimeException ignored) {
                // the refusal below is what matters; event delivery failures must not open the gate
            }
            throw new EngineException("signature_invalid", "Driver package signature is invalid", true);
        }
    }
}
