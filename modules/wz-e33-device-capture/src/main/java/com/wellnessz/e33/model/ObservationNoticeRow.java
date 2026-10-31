package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Every engine notice seen by capture (identifiers and status only) – basis of the unmatched-rate KPI. */
@Entity(name = "E33ObservationNotice")
@Table(name = "observation_notice", schema = "devicecap")
public class ObservationNoticeRow {

    @Id
    @Column(name = "observation_id")
    private UUID observationId;
    @Column(name = "device_instance_id")
    private String deviceInstanceId;
    @Column(name = "category")
    private String category;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "first_status")
    private String firstStatus;
    @Column(name = "last_status")
    private String lastStatus;
    @Column(name = "ever_unmatched")
    private boolean everUnmatched;
    @Column(name = "first_at")
    private Instant firstAt;
    @Column(name = "last_at")
    private Instant lastAt;

    protected ObservationNoticeRow() {
        // JPA
    }

    public ObservationNoticeRow(UUID observationId, String deviceInstanceId, String category, UUID sessionId, String status, Instant at) {
        this.observationId = observationId;
        this.deviceInstanceId = deviceInstanceId;
        this.category = category;
        this.sessionId = sessionId;
        this.firstStatus = status;
        this.lastStatus = status;
        this.everUnmatched = "UNMATCHED".equals(status);
        this.firstAt = at;
        this.lastAt = at;
    }

    public void update(String status, UUID sessionId, Instant at) {
        this.lastStatus = status;
        this.lastAt = at;
        if (sessionId != null) {
            this.sessionId = sessionId;
        }
        if ("UNMATCHED".equals(status)) {
            this.everUnmatched = true;
        }
    }

    public UUID getObservationId() { return observationId; }
    public String getDeviceInstanceId() { return deviceInstanceId; }
    public String getCategory() { return category; }
    public UUID getSessionId() { return sessionId; }
    public String getFirstStatus() { return firstStatus; }
    public String getLastStatus() { return lastStatus; }
    public boolean isEverUnmatched() { return everUnmatched; }
    public Instant getFirstAt() { return firstAt; }
    public Instant getLastAt() { return lastAt; }
}
