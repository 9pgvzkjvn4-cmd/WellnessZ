package com.wellnessz.e31.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.support.EngineException;

/**
 * Result-to-form bindings and routing (E31-HLR-07, contract section 11): picks the binding (the capture session's form, the
 * instance's assigned forms, else the package default), maps component keys to form fields, and delivers through the
 * {@link DeviceObservationSink} whose {@code target()} equals the binding target. The engine never writes into another
 * module's tables.
 */
@Component("e31Router")
public class Router {

    private final DeviceSpis spis;

    public Router(DeviceSpis spis) {
        this.spis = spis;
    }

    public record Selection(String formCode, String formVersion, DeviceCodes.Category category, String target, Map<String, String> fields,
                            boolean verifyBeforePost, List<String> qualityGates) {
    }

    public Selection select(DriverPackage pkg, DeviceCatalogueLookup.DeviceInstance instance, DeviceCaptureSessions.SessionBinding session,
                            String preferredForm) {
        Set<String> forms = new LinkedHashSet<>();
        if (preferredForm != null) {
            forms.add(preferredForm);
        }
        if (session != null && session.formCode() != null) {
            forms.add(session.formCode());
        }
        if (instance != null) {
            forms.addAll(instance.formCodes());
        }
        pkg.bindings().forEach(b -> forms.add(b.formCode()));
        boolean virtual = instance == null || DriverRuntime.virtual(instance.deviceInstanceId());
        for (String form : forms) {
            Optional<Selection> s = Optional.empty();
            if (!virtual) {
                s = spis.catalogue().binding(instance.deviceInstanceId(), form).map(b -> new Selection(b.formCode(), b.formVersion(), b.category(),
                        b.target(), b.fields(), b.verifyBeforePost(), b.qualityGates().isEmpty() ? pkg.qualityGates() : b.qualityGates()));
            }
            if (s.isEmpty()) {
                s = pkg.binding(form).map(b -> new Selection(b.formCode(), b.formVersion(), b.category(), b.target(), b.fields(), b.verifyBeforePost(),
                        b.qualityGates().isEmpty() ? pkg.qualityGates() : b.qualityGates()));
            }
            if (s.isPresent()) {
                Selection sel = s.get();
                boolean verify = sel.verifyBeforePost() || !virtual && instance.verifyBeforePost();
                return new Selection(sel.formCode(), sel.formVersion(), sel.category(), sel.target(), sel.fields(), verify, sel.qualityGates());
            }
        }
        throw new EngineException("binding_field_unknown", "No result-to-form binding for the device instance", true);
    }

    /** Binding key set (part of the deterministic observation id). */
    public static List<String> bindingKeys(Selection s) {
        return new ArrayList<>(s.fields().keySet());
    }

    /** Bound form fields: component values, instance parameters ({@code param:}), constants ({@code const:}) and derived values. */
    public static Map<String, Object> boundFields(Selection s, List<DeviceObservation.Component> components, Map<String, Object> derived,
                                                  Map<String, Object> parameters) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.fields().entrySet()) {
            String key = e.getKey();
            Object value;
            if (key.startsWith("param:")) {
                value = parameters == null ? null : parameters.get(key.substring(6));
            } else if (key.startsWith("const:")) {
                value = key.substring(6);
            } else if (derived != null && derived.containsKey(key)) {
                value = derived.get(key);
            } else {
                value = components.stream().filter(c -> c.key().equals(key)).findFirst().map(Router::value).orElse(null);
            }
            if (value != null) {
                out.put(e.getValue(), value);
            }
        }
        return out;
    }

    static Object value(DeviceObservation.Component c) {
        if (c.value() != null) {
            return c.value();
        }
        if ("BOOLEAN".equals(c.valueType())) {
            return Boolean.valueOf(c.text());
        }
        return c.text();
    }

    /** Delivers to the sink of the target; empty when the target is not deployed (route_target_unavailable). */
    public Optional<DeviceObservationSink.Delivery> deliver(String actor, ObservationStore.ObservationRow o, boolean verified) {
        Optional<DeviceObservationSink> sink = spis.sink(o.routeTarget());
        if (sink.isEmpty() || !sink.get().categories().contains(o.category())) {
            return Optional.empty();
        }
        DeviceObservationSink.Routed routed = new DeviceObservationSink.Routed(o.toKernel(), o.formCode(), o.formVersion(), o.boundFields(), verified);
        return Optional.ofNullable(sink.get().deliver(actor, routed));
    }
}
