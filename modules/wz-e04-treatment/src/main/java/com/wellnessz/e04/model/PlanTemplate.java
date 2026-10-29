package com.wellnessz.e04.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Treatment plan template (seeded, draft content for CMO approval). */
@Entity(name = "TxPlanTemplate")
@Table(name = "plan_template", schema = "treatment")
public class PlanTemplate {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "injury_type")
    private String injuryType;
    @Column(name = "title")
    private String title;
    @Column(name = "goals")
    private String goals;
    @Column(name = "interventions")
    private String interventions;
    @Column(name = "follow_up_days")
    private String followUpDays;
    @Column(name = "guideline_code")
    private String guidelineCode;
    @Column(name = "expected_days")
    private Integer expectedDays;
    @Column(name = "active")
    private boolean active;

    public PlanTemplate() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getInjuryType() { return injuryType; }
    public void setInjuryType(String v) { this.injuryType = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getGoals() { return goals; }
    public void setGoals(String v) { this.goals = v; }
    public String getInterventions() { return interventions; }
    public void setInterventions(String v) { this.interventions = v; }
    public String getFollowUpDays() { return followUpDays; }
    public void setFollowUpDays(String v) { this.followUpDays = v; }
    public String getGuidelineCode() { return guidelineCode; }
    public void setGuidelineCode(String v) { this.guidelineCode = v; }
    public Integer getExpectedDays() { return expectedDays; }
    public void setExpectedDays(Integer v) { this.expectedDays = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
