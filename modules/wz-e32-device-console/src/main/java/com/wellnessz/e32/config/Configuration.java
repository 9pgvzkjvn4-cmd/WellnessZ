package com.wellnessz.e32.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.wellnessz.e32.support.Docs;

/**
 * One version of a device configuration (contract §13.2). Searchable attributes are columns; the wizard state (per-step
 * status and payload, parameters with secret <em>references</em>, bindings, prerequisites, tests, reviews) lives in
 * {@link #data()}.
 */
public final class Configuration {

    public static final String DRAFT = "DRAFT";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String CERTIFIED = "CERTIFIED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String QUARANTINED = "QUARANTINED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String DECOMMISSIONED = "DECOMMISSIONED";
    /** Live states: the version that currently represents the device instance in the field. */
    public static final Set<String> LIVE = Set.of(PUBLISHED, SUSPENDED, QUARANTINED);

    UUID rowId;
    String configurationId;
    int version;
    String deviceInstanceId;
    String status;
    boolean paused;
    int currentStep;
    String modelCode;
    String option;
    String siteCode;
    String roomCode;
    String stationCode;
    String tenantCode;
    String endpointId;
    String endpointKind;
    String zone;
    String equipmentDeviceId;
    String serial;
    String udi;
    String firmware;
    String softwareVersion;
    String driverPackageId;
    String driverVersion;
    Map<String, Object> data = new LinkedHashMap<>();
    String clonedFrom;
    UUID templateId;
    String createdBy;
    Instant createdAt;
    String updatedBy;
    Instant updatedAt;
    String submittedBy;
    Instant submittedAt;
    String approvedBy;
    Instant approvedAt;
    String coApprovedBy;
    String publishedBy;
    Instant publishedAt;
    String statusReason;
    long rowVersion;

    public UUID rowId() { return rowId; }
    public String configurationId() { return configurationId; }
    public int version() { return version; }
    public String deviceInstanceId() { return deviceInstanceId; }
    public String status() { return status; }
    public boolean paused() { return paused; }
    public int currentStep() { return currentStep; }
    public String modelCode() { return modelCode; }
    public String option() { return option; }
    public String siteCode() { return siteCode; }
    public String roomCode() { return roomCode; }
    public String stationCode() { return stationCode; }
    public String tenantCode() { return tenantCode; }
    public String endpointId() { return endpointId; }
    public String endpointKind() { return endpointKind; }
    public String zone() { return zone; }
    public String equipmentDeviceId() { return equipmentDeviceId; }
    public String serial() { return serial; }
    public String udi() { return udi; }
    public String firmware() { return firmware; }
    public String softwareVersion() { return softwareVersion; }
    public String driverPackageId() { return driverPackageId; }
    public String driverVersion() { return driverVersion; }
    public Map<String, Object> data() { return data; }
    public String clonedFrom() { return clonedFrom; }
    public UUID templateId() { return templateId; }
    public String createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public String updatedBy() { return updatedBy; }
    public Instant updatedAt() { return updatedAt; }
    public String submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public String approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String coApprovedBy() { return coApprovedBy; }
    public String publishedBy() { return publishedBy; }
    public Instant publishedAt() { return publishedAt; }
    public String statusReason() { return statusReason; }

    public void setStatus(String v) { this.status = v; }
    public void setPaused(boolean v) { this.paused = v; }
    public void setCurrentStep(int v) { this.currentStep = v; }
    public void setModelCode(String v) { this.modelCode = v; }
    public void setOption(String v) { this.option = v; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public void setRoomCode(String v) { this.roomCode = v; }
    public void setStationCode(String v) { this.stationCode = v; }
    public void setTenantCode(String v) { this.tenantCode = v; }
    public void setEndpointId(String v) { this.endpointId = v; }
    public void setEndpointKind(String v) { this.endpointKind = v; }
    public void setZone(String v) { this.zone = v; }
    public void setEquipmentDeviceId(String v) { this.equipmentDeviceId = v; }
    public void setSerial(String v) { this.serial = v; }
    public void setUdi(String v) { this.udi = v; }
    public void setFirmware(String v) { this.firmware = v; }
    public void setSoftwareVersion(String v) { this.softwareVersion = v; }
    public void setDriverPackageId(String v) { this.driverPackageId = v; }
    public void setDriverVersion(String v) { this.driverVersion = v; }
    public void setTemplateId(UUID v) { this.templateId = v; }
    public void setSubmitted(String by, Instant at) { this.submittedBy = by; this.submittedAt = at; }
    public void setApproved(String by, Instant at) { this.approvedBy = by; this.approvedAt = at; }
    public void setCoApprovedBy(String v) { this.coApprovedBy = v; }
    public void setPublished(String by, Instant at) { this.publishedBy = by; this.publishedAt = at; }
    public void setStatusReason(String v) { this.statusReason = v; }

    // ------------------------------------------------------------------ wizard state helpers (data document)

    public Map<String, Object> step(int n) {
        Map<String, Object> steps = Docs.map(data.computeIfAbsent("steps", k -> new LinkedHashMap<String, Object>()));
        return Docs.map(steps.computeIfAbsent(String.valueOf(n), k -> new LinkedHashMap<>(Map.of("status", "NOT_STARTED"))));
    }

    public String stepStatus(int n) {
        return Docs.str(step(n).get("status"));
    }

    public Map<String, Object> stepData(int n) {
        return Docs.map(step(n).computeIfAbsent("data", k -> new LinkedHashMap<String, Object>()));
    }

    public void setStep(int n, String status, Map<String, Object> payload, List<Map<String, Object>> errors, String actor, Instant at) {
        Map<String, Object> s = step(n);
        s.put("status", status);
        if (payload != null) {
            s.put("data", payload);
        }
        if (errors == null || errors.isEmpty()) {
            s.remove("errors");
        } else {
            s.put("errors", errors);
        }
        s.put("updatedBy", actor);
        s.put("updatedAt", at.toString());
    }

    public Map<String, Object> parameters() {
        return Docs.map(stepData(4).computeIfAbsent("parameters", k -> new LinkedHashMap<String, Object>()));
    }

    public Map<String, Object> common() {
        return Docs.map(stepData(4).computeIfAbsent("common", k -> new LinkedHashMap<String, Object>()));
    }

    /** Secret name → {@code devsec:} reference. */
    public Map<String, Object> secrets() {
        return Docs.map(data.computeIfAbsent("secrets", k -> new LinkedHashMap<String, Object>()));
    }

    public List<Object> bindings() {
        return Docs.list(stepData(5).get("bindings"));
    }

    public List<String> formCodes() {
        return Docs.strings(stepData(5).get("formCodes"));
    }

    public List<String> associationMethods() {
        return Docs.strings(stepData(5).get("associationMethods"));
    }

    public List<Object> tests() {
        return Docs.list(data.computeIfAbsent("tests", k -> new ArrayList<>()));
    }

    /** Users who changed steps 4–6 of this version (four-eyes: none of them may approve). */
    public Set<String> stepEditors() {
        return new LinkedHashSet<>(Docs.strings(data.get("stepEditors")));
    }

    public void addStepEditor(String actor) {
        Set<String> s = stepEditors();
        s.add(actor);
        data.put("stepEditors", new ArrayList<>(s));
    }

    public boolean live() {
        return LIVE.contains(status);
    }

    public boolean verifyBeforePost() {
        return Docs.bool(common().get("verifyBeforePost"), true);
    }
}
