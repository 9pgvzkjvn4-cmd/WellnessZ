package com.wellnessz.masterdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Duty assignment from the crew rostering, shift-planning and facility systems (increment 5): who was on which flight,
 * shift or shared facility and when. Used for critical-incident affected-person lists (E09), post-incident testing
 * (E10) and communicable-disease contact tracing (E11). Operational data only – no health information.
 */
@Entity
@Table(name = "duty_assignment", schema = "masterdata")
public class DutyAssignment {

    @Id
    private UUID id;
    /** CREW_ROSTER, SHIFT, FACILITY. */
    @Column(name = "source", nullable = false, length = 20)
    private String source;
    /** Source-system key, idempotent per source. */
    @Column(name = "source_ref", nullable = false, length = 120)
    private String sourceRef;
    /** Flight number (e.g. WZ101), shift code or facility code. */
    @Column(name = "duty_ref", nullable = false, length = 60)
    private String dutyRef;
    @Column(name = "duty_date", nullable = false)
    private LocalDate dutyDate;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    /** Duty role, e.g. CAPTAIN, FIRST_OFFICER, CABIN_CREW, RAMP_AGENT, CATERING. */
    @Column(name = "duty_role", length = 40)
    private String dutyRole;
    /** Departure / base / location code. */
    @Column(name = "location_code", length = 40)
    private String locationCode;
    /** Arrival station for flights (layover city), otherwise null. */
    @Column(name = "destination_code", length = 40)
    private String destinationCode;
    /** Aircraft registration or work area – the proximity group used for contact tracing. */
    @Column(name = "proximity_group", length = 60)
    private String proximityGroup;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected DutyAssignment() {
    }

    public DutyAssignment(String source, String sourceRef, String dutyRef, LocalDate dutyDate, Instant startsAt, Instant endsAt,
                          String employeeId, String dutyRole, String locationCode, String destinationCode, String proximityGroup,
                          Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.sourceRef = sourceRef;
        update(dutyRef, dutyDate, startsAt, endsAt, employeeId, dutyRole, locationCode, destinationCode, proximityGroup, receivedAt);
    }

    public final void update(String dutyRef, LocalDate dutyDate, Instant startsAt, Instant endsAt, String employeeId, String dutyRole,
                             String locationCode, String destinationCode, String proximityGroup, Instant receivedAt) {
        this.dutyRef = dutyRef;
        this.dutyDate = dutyDate;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.employeeId = employeeId;
        this.dutyRole = dutyRole;
        this.locationCode = locationCode;
        this.destinationCode = destinationCode;
        this.proximityGroup = proximityGroup;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getDutyRef() {
        return dutyRef;
    }

    public LocalDate getDutyDate() {
        return dutyDate;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getDutyRole() {
        return dutyRole;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getDestinationCode() {
        return destinationCode;
    }

    public String getProximityGroup() {
        return proximityGroup;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
