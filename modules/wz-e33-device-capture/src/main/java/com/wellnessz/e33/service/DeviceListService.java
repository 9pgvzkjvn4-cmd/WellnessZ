package com.wellnessz.e33.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.FallbackChain;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureBatchRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;

/**
 * Station-aware device list for the "Capture from device" control (E33-HLR-01, E33-F01): published instances at the station
 * that serve the form, with a plain status – AVAILABLE, BUSY (another session holds it), LOCKED_OUT (calibration out of date or
 * out of service in the E14 register, daily check / QC due or failed – E32 lockout) or OFFLINE (connector stale / silent /
 * isolated, when the engine reports health). No technical parameters are returned.
 */
@Service
public class DeviceListService {

    public static final List<String> HOLDING = List.of("LISTENING", "PREVIEW");

    /**
     * @param heldBy operator holding a BUSY device (a username, never an employee)
     */
    public record DeviceChoice(String deviceInstanceId, String displayName, String model, String option, String status, String lockReason,
                               String nextAction, String heldBy) {

        public boolean available() {
            return "AVAILABLE".equals(status);
        }
    }

    private static final Map<DeviceCodes.Category, String> LABEL = Map.ofEntries(
            Map.entry(DeviceCodes.Category.AUDIOMETRY, "Audiometer"), Map.entry(DeviceCodes.Category.SPIROMETRY, "Spirometer"),
            Map.entry(DeviceCodes.Category.VISION, "Vision tester"), Map.entry(DeviceCodes.Category.ECG, "Electrocardiograph"),
            Map.entry(DeviceCodes.Category.VITALS, "Vital signs monitor"), Map.entry(DeviceCodes.Category.BODY_COMPOSITION, "Body composition scale"),
            Map.entry(DeviceCodes.Category.ABPM_HOLTER, "ABPM / Holter recorder"), Map.entry(DeviceCodes.Category.BREATH_ALCOHOL, "Breath alcohol tester"),
            Map.entry(DeviceCodes.Category.DRUG_SCREEN, "Drug screen reader"), Map.entry(DeviceCodes.Category.POC, "Point-of-care analyser"),
            Map.entry(DeviceCodes.Category.FIT_TEST, "Fit tester"), Map.entry(DeviceCodes.Category.ERGONOMICS, "Hand-arm assessment device"),
            Map.entry(DeviceCodes.Category.EXPOSURE, "Exposure monitor"), Map.entry(DeviceCodes.Category.WEARABLE, "Wearable"),
            Map.entry(DeviceCodes.Category.IMAGING, "Imaging device"));

    private final DeviceSpis spis;
    private final List<DeviceCalibrationCheck> calibration;
    private final E33CaptureSessionRepository sessions;
    private final E33CaptureBatchRepository batches;
    private final FormRegistry forms;
    private final CaptureSupport support;

    public DeviceListService(DeviceSpis spis, List<DeviceCalibrationCheck> calibration, E33CaptureSessionRepository sessions,
                             E33CaptureBatchRepository batches, FormRegistry forms, CaptureSupport support) {
        this.spis = spis;
        this.calibration = List.copyOf(calibration);
        this.sessions = sessions;
        this.batches = batches;
        this.forms = forms;
        this.support = support;
    }

    /** The device list of a form at a station. */
    @Transactional(readOnly = true)
    public List<DeviceChoice> devices(String formCode, String siteCode, String stationCode) {
        FormRegistry.Form form = forms.require(formCode);
        LocalDate today = support.today();
        List<DeviceCatalogueLookup.DeviceInstance> listed = atStation(form, siteCode, stationCode);
        Map<String, String> names = displayNames(listed, form.category());
        List<DeviceChoice> out = new ArrayList<>();
        for (DeviceCatalogueLookup.DeviceInstance i : listed) {
            DeviceChoice c = evaluate(i, form.category(), today);
            out.add(new DeviceChoice(c.deviceInstanceId(), names.getOrDefault(i.deviceInstanceId(), c.displayName()), c.model(), c.option(),
                    c.status(), c.lockReason(), c.nextAction(), c.heldBy()));
        }
        return out;
    }

    private List<DeviceCatalogueLookup.DeviceInstance> atStation(FormRegistry.Form form, String siteCode, String stationCode) {
        return spis.catalogue().instancesAtStation(siteCode, stationCode, form.category()).stream()
                .filter(i -> i.published() && servesForm(i, form.formCode())).toList();
    }

    /**
     * The name of a session's device as the station list shows it (disambiguated against the other devices of the form at
     * the station), {@code null} without a device.
     */
    @Transactional(readOnly = true)
    public String displayName(String deviceInstanceId, String formCode, String siteCode, String fallbackStation) {
        if (deviceInstanceId == null) {
            return null;
        }
        FormRegistry.Form form = forms.require(formCode);
        Optional<DeviceCatalogueLookup.DeviceInstance> i = instance(deviceInstanceId);
        String station = i.map(DeviceCatalogueLookup.DeviceInstance::stationCode).orElse(fallbackStation);
        String plain = displayName(form.category(), station);
        if (i.isEmpty() || siteCode == null || station == null) {
            return plain;
        }
        return displayNames(atStation(form, siteCode, station), form.category()).getOrDefault(deviceInstanceId, plain);
    }

    /**
     * Station display names, unique per station (contract 14.2 "Spirometer – Room 2"): when two devices at the station would
     * get the same name, the model is appended ("Vital signs monitor – Room 1 (Connex Spot)"); when the model is shared too,
     * a short asset tag – the E14 equipment id, else the end of the serial number, else the instance id
     * ("… (Connex Spot · SPIRO-HQ-01)"). No technical parameters.
     */
    static Map<String, String> displayNames(List<DeviceCatalogueLookup.DeviceInstance> instances, DeviceCodes.Category category) {
        Map<String, String> base = new java.util.LinkedHashMap<>();
        instances.forEach(i -> base.put(i.deviceInstanceId(), displayName(category, i.stationCode())));
        Map<String, String> out = new java.util.LinkedHashMap<>(base);
        Map<String, List<DeviceCatalogueLookup.DeviceInstance>> byName = new java.util.LinkedHashMap<>();
        instances.forEach(i -> byName.computeIfAbsent(base.get(i.deviceInstanceId()), k -> new ArrayList<>()).add(i));
        for (Map.Entry<String, List<DeviceCatalogueLookup.DeviceInstance>> e : byName.entrySet()) {
            List<DeviceCatalogueLookup.DeviceInstance> group = e.getValue();
            if (group.size() < 2) {
                continue;
            }
            Map<String, Long> models = new java.util.HashMap<>();
            group.forEach(i -> models.merge(String.valueOf(i.model()), 1L, Long::sum));
            java.util.Set<String> used = new java.util.HashSet<>();
            for (DeviceCatalogueLookup.DeviceInstance i : group) {
                String model = i.model() == null || i.model().isBlank() ? null : i.model();
                String suffix = model != null && models.get(model) == 1 ? model
                        : (model == null ? "" : model + " · ") + assetTag(i);
                String name = e.getKey() + " (" + suffix + ")";
                if (!used.add(name)) {
                    name = e.getKey() + " (" + suffix + " · " + i.deviceInstanceId() + ")";
                    used.add(name);
                }
                out.put(i.deviceInstanceId(), name);
            }
        }
        return out;
    }

    static String assetTag(DeviceCatalogueLookup.DeviceInstance i) {
        if (i.equipmentDeviceId() != null && !i.equipmentDeviceId().isBlank()) {
            return i.equipmentDeviceId();
        }
        if (i.serial() != null && !i.serial().isBlank()) {
            String serial = i.serial().strip();
            return "SN …" + serial.substring(Math.max(0, serial.length() - 4));
        }
        return i.deviceInstanceId();
    }

    /** Status of one instance for a form (404 when it is not a published instance serving the form at the site). */
    @Transactional(readOnly = true)
    public DeviceChoice choice(String deviceInstanceId, String formCode, String siteCode) {
        FormRegistry.Form form = forms.require(formCode);
        DeviceCatalogueLookup.DeviceInstance i = instance(deviceInstanceId)
                .filter(DeviceCatalogueLookup.DeviceInstance::published)
                .filter(x -> siteCode == null || siteCode.equals(x.siteCode()))
                .filter(x -> x.categories().isEmpty() || x.categories().contains(form.category()))
                .filter(x -> servesForm(x, formCode))
                .orElseThrow(() -> ApiException.notFound("Device"));
        return evaluate(i, form.category(), support.today());
    }

    public Optional<DeviceCatalogueLookup.DeviceInstance> instance(String deviceInstanceId) {
        return deviceInstanceId == null ? Optional.empty() : spis.catalogue().instance(deviceInstanceId);
    }

    /** Available alternates of the same category at the station (fall-back step 1), excluding a device. */
    @Transactional(readOnly = true)
    public List<FallbackChain.Alternate> alternates(String formCode, String siteCode, String stationCode, String exclude) {
        return devices(formCode, siteCode, stationCode).stream().filter(DeviceChoice::available)
                .filter(c -> !c.deviceInstanceId().equals(exclude))
                .map(c -> new FallbackChain.Alternate(c.deviceInstanceId(), c.displayName())).toList();
    }

    /**
     * An OCR package exists for the category (fall-back step 2): a P9 instance at the station, or the category is listed in
     * {@code device.capture.ocr-categories} (the generic display-OCR package of the platform). Never for restricted categories.
     */
    public boolean ocrAvailable(DeviceCodes.Category category, String siteCode, String stationCode) {
        if (category == DeviceCodes.Category.BREATH_ALCOHOL || category == DeviceCodes.Category.DRUG_SCREEN) {
            return false;
        }
        boolean p9 = spis.catalogue().instancesAtStation(siteCode, stationCode, category).stream()
                .anyMatch(i -> i.published() && i.option() == DeviceCodes.Option.P9_IMAGE_OCR);
        return p9 || support.configCsv("device.capture.ocr-categories",
                "AUDIOMETRY,SPIROMETRY,VISION,ECG,VITALS,BODY_COMPOSITION,ABPM_HOLTER,POC,FIT_TEST,ERGONOMICS").contains(category.name());
    }

    static boolean servesForm(DeviceCatalogueLookup.DeviceInstance i, String formCode) {
        return i.formCodes().isEmpty() || i.formCodes().contains(formCode);
    }

    DeviceChoice evaluate(DeviceCatalogueLookup.DeviceInstance i, DeviceCodes.Category category, LocalDate today) {
        String name = displayName(category, i.stationCode());
        String option = i.option() == null ? null : i.option().code();
        List<CaptureSession> holders = sessions.findByDeviceInstanceIdAndStateIn(i.deviceInstanceId(), HOLDING).stream()
                .filter(s -> "FORM".equals(s.getMode())).toList();
        if (!holders.isEmpty()) {
            return new DeviceChoice(i.deviceInstanceId(), name, i.model(), option, "BUSY", "device_busy",
                    "Wait for the current capture to finish or choose another device", holders.get(0).getOperator());
        }
        boolean inBatch = batches.findByStatus("OPEN").stream()
                .anyMatch(b -> CaptureSupport.csv(b.getDeviceInstanceIds()).contains(i.deviceInstanceId()));
        if (inBatch) {
            return new DeviceChoice(i.deviceInstanceId(), name, i.model(), option, "BUSY", "device_in_batch",
                    "The device is paired to a screening batch", null);
        }
        if (i.equipmentDeviceId() != null) {
            for (DeviceCalibrationCheck c : calibration) {
                DeviceCalibrationCheck.Status st = c.check(i.equipmentDeviceId(), today);
                if (st != null && st.verdict() == DeviceCalibrationCheck.Verdict.OUT_OF_CALIBRATION) {
                    return locked(i, name, option, "device_out_of_calibration", "Ask the biomedical team to recalibrate the device");
                }
                if (st != null && st.verdict() == DeviceCalibrationCheck.Verdict.OUT_OF_SERVICE) {
                    return locked(i, name, option, "device_out_of_service", "The device is out of service – use another device");
                }
            }
        }
        DeviceCatalogueLookup.QcStatus qc = spis.catalogue().qcStatus(i.deviceInstanceId(), today, null);
        if (qc != null && qc.blocks()) {
            String code = "FAILED".equals(qc.verdict()) ? "qc_failed" : "qc_due";
            return locked(i, name, option, code, "Record the daily check" + (qc.checkType() == null ? "" : " (" + qc.checkType() + ")"));
        }
        String health = health(i.deviceInstanceId());
        if ("STALE".equals(health) || "SILENT".equals(health) || "ISOLATED".equals(health)) {
            return new DeviceChoice(i.deviceInstanceId(), name, i.model(), option, "OFFLINE", "device_offline",
                    "Check the device is switched on and connected, or choose another device", null);
        }
        return new DeviceChoice(i.deviceInstanceId(), name, i.model(), option, "AVAILABLE", null, null, null);
    }

    private static DeviceChoice locked(DeviceCatalogueLookup.DeviceInstance i, String name, String option, String reason, String next) {
        return new DeviceChoice(i.deviceInstanceId(), name, i.model(), option, "LOCKED_OUT", reason, next, null);
    }

    /**
     * Connector health from the engine when it is deployed; {@code null} when unknown. The engine reports {@code SILENT} for a
     * connector it has never seen ({@code lastSeenAt == null} – a newly published device that only transmits when it measures):
     * that is "no data yet", not "offline", and must not force every first capture into the fall-back chain.
     */
    private String health(String deviceInstanceId) {
        try {
            DeviceDiagnostics.ConnectorHealth h = spis.diagnostics().health(deviceInstanceId);
            if (h == null || h.lastSeenAt() == null && !"ISOLATED".equals(h.state())) {
                return null;
            }
            return h.state();
        } catch (ApiException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** "Spirometer – Room 2" from the category and a station code such as {@code LHR-OH-ROOM-2}. */
    public static String displayName(DeviceCodes.Category category, String stationCode) {
        String label = LABEL.getOrDefault(category, "Device");
        if (stationCode == null || stationCode.isBlank()) {
            return label;
        }
        String[] parts = stationCode.split("-");
        String place = parts.length >= 2 && parts[parts.length - 1].matches("\\d+")
                ? cap(parts[parts.length - 2]) + " " + parts[parts.length - 1]
                : cap(parts[parts.length - 1]);
        return label + " – " + place;
    }

    private static String cap(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }
}
