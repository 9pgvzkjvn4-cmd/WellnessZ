package com.wellnessz.e31.pipeline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;

/**
 * Published device instances (E32 catalogue) and the virtual simulator instances {@code SIM-<adapter>} built from the
 * bundled sample packages (no tokens, test traffic only, contract section 19).
 */
@Component("e31InstanceResolver")
public class InstanceResolver {

    private final DeviceSpis spis;
    private final DriverRuntime drivers;

    public InstanceResolver(DeviceSpis spis, DriverRuntime drivers) {
        this.spis = spis;
        this.drivers = drivers;
    }

    public Optional<DeviceCatalogueLookup.DeviceInstance> find(String deviceInstanceId, String packageId, String version, String tenant) {
        if (deviceInstanceId == null) {
            return Optional.empty();
        }
        if (DriverRuntime.virtual(deviceInstanceId)) {
            DeviceCatalogueLookup.DeviceInstance context = new DeviceCatalogueLookup.DeviceInstance(deviceInstanceId, null, null, null, null, null,
                    null, null, null, null, "PUBLISHED", "SIM", null, deviceInstanceId, packageId, version, Set.of(), Map.of(), List.of(), true,
                    List.of(), "UTC", tenant, 0);
            try {
                return Optional.of(virtual(deviceInstanceId, drivers.resolve(packageId, version, context), tenant));
            } catch (com.wellnessz.e31.support.EngineException e) {
                return Optional.empty();
            }
        }
        if (deviceInstanceId.startsWith(MANUAL_PREFIX)) {
            return Optional.of(manual(deviceInstanceId, tenant));
        }
        return spis.catalogue().instance(deviceInstanceId);
    }

    /** Pseudo instance of a manual entry without a registered device ({@code MANUAL-<category>}, P10, fall-back chain). */
    public static final String MANUAL_PREFIX = "MANUAL-";

    public static DeviceCatalogueLookup.DeviceInstance manual(String id, String tenant) {
        return new DeviceCatalogueLookup.DeviceInstance(id, null, null, null, "Manual entry", null, null, null, null,
                DeviceCodes.Option.P10_MANUAL, "PUBLISHED", null, null, null, null, null, Set.of(), Map.of(), List.of(), true, List.of(), "UTC",
                tenant, 0);
    }

    public static DeviceCatalogueLookup.DeviceInstance virtual(String id, DriverPackage p, String tenant) {
        Set<DeviceCodes.Category> categories = new LinkedHashSet<>();
        p.bindings().forEach(b -> categories.add(b.category()));
        List<String> forms = p.bindings().stream().map(DriverPackage.Binding::formCode).toList();
        DeviceCodes.Option option = p.options().isEmpty() ? DeviceCodes.Option.P10_MANUAL : DeviceCodes.Option.ofCode(p.options().get(0));
        return new DeviceCatalogueLookup.DeviceInstance(id, p.models().get(0), p.family(), "Simulator", p.packageId(), "SIM-SERIAL", null, "sim",
                null, option, "PUBLISHED", "SIM", "SIM-STATION", id, p.packageId(), p.version(), categories, Map.of(),
                List.of("WORKLIST", "SESSION", "BADGE", "VENDOR_ID_MATCH"), true, forms, "UTC", tenant, 0);
    }
}
