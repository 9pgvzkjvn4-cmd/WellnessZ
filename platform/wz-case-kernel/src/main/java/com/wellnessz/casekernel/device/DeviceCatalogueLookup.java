package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read model of the published device configuration, implemented by the console (E32) and used by the engine (E31),
 * capture (E33) and security (E34). Only <b>published</b> configuration versions are visible here; drafts stay inside E32.
 * Parameters never contain secrets – secret values are references resolved through {@link DeviceSecretStore}.
 * Contract section 5.
 */
public interface DeviceCatalogueLookup {

    /** A published device instance (a certified model at a site / station with its connectivity configuration). */
    Optional<DeviceInstance> instance(String deviceInstanceId);

    /**
     * Device instances served by an authenticated endpoint. A gateway or Device Host agent fronts several devices; the
     * device serial (or vendor device id) from the message selects one, {@code null} returns all.
     */
    List<DeviceInstance> instancesForEndpoint(String endpointId, String deviceSerial);

    /** Published, in-service instances at a station (or paired to a tablet / kiosk endpoint) that serve a category. */
    List<DeviceInstance> instancesAtStation(String siteCode, String stationCode, DeviceCodes.Category category);

    /** A signed driver package version (manifest JSON as published, canonical form). */
    Optional<DriverPackage> driverPackage(String packageId, String version);

    /** Result-to-form binding of an instance for a form code (falls back to the model / family default binding). */
    Optional<FormBinding> binding(String deviceInstanceId, String formCode);

    /** QC status (daily biological check, 3-L syringe, QC lot, BP validation interval …) on a day, optionally for a lot. */
    QcStatus qcStatus(String deviceInstanceId, LocalDate on, String lot);

    /**
     * @param status            PUBLISHED | SUSPENDED | QUARANTINED | DECOMMISSIONED (only PUBLISHED instances accept results)
     * @param option            connectivity option chosen in the wizard
     * @param parameters        non-secret option parameters (P1–P10 schemas, contract section 13.3)
     * @param associationMethods ordered association methods allowed (WORKLIST, SESSION, BADGE, VENDOR_ID_MATCH)
     * @param verifyBeforePost  default verification rule of the instance (a binding may tighten it)
     * @param formCodes         forms the instance is assigned to
     * @param configVersion     published configuration version (part of provenance)
     */
    record DeviceInstance(String deviceInstanceId, String modelCode, DeviceCodes.Family family, String manufacturer, String model,
                          String serial, String udi, String firmware, String equipmentDeviceId, DeviceCodes.Option option, String status,
                          String siteCode, String stationCode, String endpointId, String driverPackageId, String driverVersion,
                          Set<DeviceCodes.Category> categories, Map<String, Object> parameters, List<String> associationMethods,
                          boolean verifyBeforePost, List<String> formCodes, String timeZone, String tenantCode, int configVersion) {

        public DeviceInstance {
            categories = categories == null ? Set.of() : Set.copyOf(categories);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            associationMethods = associationMethods == null ? List.of() : List.copyOf(associationMethods);
            formCodes = formCodes == null ? List.of() : List.copyOf(formCodes);
        }

        public boolean published() {
            return "PUBLISHED".equals(status);
        }
    }

    /**
     * @param manifestJson   the package JSON exactly as signed (canonical: UTF-8, keys sorted, no insignificant whitespace)
     * @param sha256         SHA-256 of {@code manifestJson}
     * @param signatureKeyId key id of the signature verified by E34 at import
     */
    record DriverPackage(String packageId, String version, DeviceCodes.Family family, String manifestJson, String sha256,
                         String signatureKeyId, String signatureBase64) {
    }

    /**
     * @param target       routing target ({@link DeviceObservationSink#target()})
     * @param fields       component key → form field path
     * @param qualityGates gate codes to run, in order (contract section 10)
     */
    record FormBinding(String formCode, String formVersion, DeviceCodes.Category category, String target, Map<String, String> fields,
                       boolean verifyBeforePost, List<String> qualityGates) {

        public FormBinding {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            qualityGates = qualityGates == null ? List.of() : List.copyOf(qualityGates);
        }
    }

    /**
     * @param verdict   VALID | DUE | FAILED | NOT_REQUIRED | UNKNOWN (DUE and FAILED block posting – E32-HLR-04 lockout)
     * @param checkType e.g. {@code AUDIO_DAILY_BIOLOGICAL}, {@code SPIRO_SYRINGE_3L}, {@code POC_QC_LOT}
     */
    record QcStatus(String verdict, String checkType, Instant lastCheckAt, Instant dueAt, String lot, LocalDate lotExpiry) {

        public static QcStatus unknown() {
            return new QcStatus("UNKNOWN", null, null, null, null, null);
        }

        public boolean blocks() {
            return "DUE".equals(verdict) || "FAILED".equals(verdict);
        }
    }

    /** Default when E32 is not deployed: nothing is published, so the engine accepts nothing. */
    DeviceCatalogueLookup EMPTY = new DeviceCatalogueLookup() {
        @Override
        public Optional<DeviceInstance> instance(String deviceInstanceId) {
            return Optional.empty();
        }

        @Override
        public List<DeviceInstance> instancesForEndpoint(String endpointId, String deviceSerial) {
            return List.of();
        }

        @Override
        public List<DeviceInstance> instancesAtStation(String siteCode, String stationCode, DeviceCodes.Category category) {
            return List.of();
        }

        @Override
        public Optional<DriverPackage> driverPackage(String packageId, String version) {
            return Optional.empty();
        }

        @Override
        public Optional<FormBinding> binding(String deviceInstanceId, String formCode) {
            return Optional.empty();
        }

        @Override
        public QcStatus qcStatus(String deviceInstanceId, LocalDate on, String lot) {
            return QcStatus.unknown();
        }
    };
}
