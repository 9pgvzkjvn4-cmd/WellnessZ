package com.wellnessz.e04.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** E-referral service catalogue entry with its required data set (E04-BL-005). */
@Entity(name = "TxServiceDefinition")
@Table(name = "service_catalogue", schema = "treatment")
public class ServiceDefinition {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "provider_types")
    private String providerTypes;
    @Column(name = "required_fields")
    private String requiredFields;
    @Column(name = "report_due_days")
    private int reportDueDays;
    @Column(name = "urgent_appointment_hours")
    private int urgentAppointmentHours;
    @Column(name = "routine_appointment_days")
    private int routineAppointmentDays;
    @Column(name = "active")
    private boolean active;

    public ServiceDefinition() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getProviderTypes() { return providerTypes; }
    public void setProviderTypes(String v) { this.providerTypes = v; }
    public String getRequiredFields() { return requiredFields; }
    public void setRequiredFields(String v) { this.requiredFields = v; }
    public int getReportDueDays() { return reportDueDays; }
    public void setReportDueDays(int v) { this.reportDueDays = v; }
    public int getUrgentAppointmentHours() { return urgentAppointmentHours; }
    public void setUrgentAppointmentHours(int v) { this.urgentAppointmentHours = v; }
    public int getRoutineAppointmentDays() { return routineAppointmentDays; }
    public void setRoutineAppointmentDays(int v) { this.routineAppointmentDays = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
